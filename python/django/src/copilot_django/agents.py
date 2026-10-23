"""Declarative agents: ``CopilotAgent`` subclasses, ``@prompt`` methods, and conversations."""

from __future__ import annotations

import functools
import inspect
import re
import shutil
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any, ClassVar, get_type_hints

from asgiref.sync import sync_to_async
from django.conf import settings as django_settings
from django.utils.module_loading import import_string

from copilot_agents import (
    AgentContext,
    AgentDefinition,
    AgentInvoker,
    ConfigIssue,
    CopilotAgentHooks,
    CopilotConfigurationError,
    CopilotConversationNotFound,
    CopilotTool,

    policies,
)

from copilot_agents.tools import as_tool

from . import registry, runtime, templating
from .conf import get_settings
from .context import current_request, user_partition

_UNSET: Any = object()


def default_name(class_name: str) -> str:
    """``SupportAgent`` -> ``support``; ``OrderTriageAgent`` -> ``order_triage``."""
    base = class_name[: -len("Agent")] if class_name.endswith("Agent") and class_name != "Agent" else class_name
    return re.sub(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", "_", base).lower()


class CopilotAgent:
    """Base class for Copilot agents.

    Subclasses register themselves on definition (set ``abstract = True`` to opt out).
    Settings in ``COPILOT["AGENTS"][name]`` override class attributes, which override
    ``COPILOT["DEFAULTS"]``. Attributes that name tools, hooks, policies, resolvers,
    listeners, or customizers accept objects or dotted import paths.
    """

    abstract: ClassVar[bool] = True
    name: ClassVar[str] = ""

    instructions: ClassVar[str | None] = None
    instructions_template: ClassVar[str | None] = None
    instructions_mode: ClassVar[str | None] = None
    system_message: ClassVar[Mapping[str, Any] | None] = None
    model: ClassVar[str | None] = None
    reasoning_effort: ClassVar[str | None] = None
    provider: ClassVar[str | Mapping[str, Any] | None] = None
    tools: ClassVar[list[Any] | tuple[Any, ...] | None] = None
    mcp_servers: ClassVar[list[str] | Mapping[str, Any] | None] = None
    available_tools: ClassVar[list[str] | None] = None
    excluded_tools: ClassVar[list[str] | None] = None
    excluded_built_in_agents: ClassVar[list[str] | None] = None
    built_in_tools: ClassVar[bool | None] = None
    tool_search: ClassVar[Mapping[str, Any] | None] = None
    working_directory: ClassVar[str | Path | Callable[[AgentContext], str | None] | None] = None
    additional_directories: ClassVar[list[str] | None] = None
    permissions: ClassVar[Any] = None
    hooks: ClassVar[list[Any] | None] = None
    listeners: ClassVar[list[Any] | None] = None
    customizers: ClassVar[list[Any] | None] = None
    token_resolver: ClassVar[Any] = None
    timeout: ClassVar[float | None] = None

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        if "abstract" not in cls.__dict__:
            cls.abstract = False
        if not cls.__dict__.get("name"):
            cls.name = default_name(cls.__name__)
        if not cls.abstract:
            registry.register_agent(cls)

    def __init__(self, *, user: Any = _UNSET, request: Any = _UNSET) -> None:
        """``user`` and ``request`` default to the current request (see the middleware)."""
        self._user = user
        self._request = request

    def __repr__(self) -> str:
        return f"<{type(self).__name__} {self.name!r}>"

    # -- definition -------------------------------------------------------------------

    @classmethod
    def definition(cls) -> AgentDefinition:
        """The resolved definition (cached; rebuilt when settings change)."""
        return cls._built()[0]

    @classmethod
    def invoker(cls) -> AgentInvoker:
        return cls._built()[1]

    @classmethod
    def _built(cls) -> tuple[AgentDefinition, AgentInvoker]:
        if cls.abstract:
            raise CopilotConfigurationError(f"{cls.__qualname__} is abstract")

        def build() -> tuple[AgentDefinition, AgentInvoker]:
            definition = build_definition(cls)
            invoker = AgentInvoker(
                definition,
                runtime.get_runtime(),
                sync_runner=runtime.sync_runner,
                fallback=runtime.fallback_dispatcher,
                partition_fallback=user_partition,
            )
            return definition, invoker

        return registry.cached(cls, build)

    # -- identity ---------------------------------------------------------------------

    def _identity(self) -> tuple[Any, Any]:
        request = current_request() if self._request is _UNSET else self._request
        if self._user is not _UNSET:
            return self._user, request
        return getattr(request, "user", None), request

    async def _aidentity(self) -> tuple[Any, Any]:
        request = current_request() if self._request is _UNSET else self._request
        if self._user is not _UNSET:
            return self._user, request
        if request is None:
            return None, None
        if hasattr(request, "auser"):
            return await request.auser(), request
        return getattr(request, "user", None), request

    # -- imperative API ---------------------------------------------------------------

    def ask(
        self,
        prompt: str,
        *,
        result: type[Any] | None = None,
        conversation_id: str | None = None,
        attributes: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> Any:
        """Send ``prompt``; one-shot (ephemeral session) unless ``conversation_id`` is given."""
        user, request = self._identity()
        return self.invoker().invoke(
            prompt,
            conversation_id=conversation_id,
            result=result,
            user=user,
            request=request,
            attributes=attributes,
            timeout=timeout,
        )

    async def aask(
        self,
        prompt: str,
        *,
        result: type[Any] | None = None,
        conversation_id: str | None = None,
        attributes: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> Any:
        user, request = await self._aidentity()
        invoker = await sync_to_async(self.invoker)()
        return await invoker.ainvoke(
            prompt,
            conversation_id=conversation_id,
            result=result,
            user=user,
            request=request,
            attributes=attributes,
            timeout=timeout,
        )

    def conversation(self, conversation_id: str) -> Conversation:
        """A stateful, resumable conversation (created on first message)."""
        return Conversation(self, conversation_id)

    def resume(self, conversation_id: str) -> Conversation:
        """An existing conversation; raises :class:`CopilotConversationNotFound` if missing."""
        conversation = Conversation(self, conversation_id, require_existing=True)
        if not conversation.exists():
            raise CopilotConversationNotFound(self.name, conversation_id)
        return conversation

    async def aresume(self, conversation_id: str) -> Conversation:
        conversation = Conversation(self, conversation_id, require_existing=True)
        if not await conversation.aexists():
            raise CopilotConversationNotFound(self.name, conversation_id)
        return conversation


class Conversation:
    """A handle to one conversation of an agent and caller.

    Sessions are opened per message and disconnected afterwards, so a conversation can be
    continued from any thread, worker, or process that shares the runtime state.
    """

    def __init__(self, agent: CopilotAgent, conversation_id: str, *, require_existing: bool = False) -> None:
        if not conversation_id:
            raise ValueError("conversation_id must be a non-empty string")
        self.agent = agent
        self.conversation_id = conversation_id
        self.require_existing = require_existing

    def __repr__(self) -> str:
        return f"<Conversation {self.agent.name}:{self.conversation_id}>"

    @property
    def session_id(self) -> str:
        user, request = self.agent._identity()
        return self.agent.invoker().session_id(self.conversation_id, user=user, request=request)

    def send(
        self,
        prompt: str,
        *,
        result: type[Any] | None = None,
        attributes: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> Any:
        user, request = self.agent._identity()
        return self.agent.invoker().invoke(
            prompt,
            conversation_id=self.conversation_id,
            result=result,
            user=user,
            request=request,
            attributes=attributes,
            require_existing=self.require_existing,
            timeout=timeout,
        )

    async def asend(
        self,
        prompt: str,
        *,
        result: type[Any] | None = None,
        attributes: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> Any:
        user, request = await self.agent._aidentity()
        invoker = await sync_to_async(self.agent.invoker)()
        return await invoker.ainvoke(
            prompt,
            conversation_id=self.conversation_id,
            result=result,
            user=user,
            request=request,
            attributes=attributes,
            require_existing=self.require_existing,
            timeout=timeout,
        )

    def exists(self) -> bool:
        user, request = self.agent._identity()
        return self.agent.invoker().exists(self.conversation_id, user=user, request=request)

    async def aexists(self) -> bool:
        user, request = await self.agent._aidentity()
        invoker = await sync_to_async(self.agent.invoker)()
        return await invoker.aexists(self.conversation_id, user=user, request=request)

    def delete(self) -> None:
        """Delete the runtime session and its isolated workspace."""
        user, request = self.agent._identity()
        self.agent.invoker().delete(self.conversation_id, user=user, request=request)

    async def adelete(self) -> None:
        user, request = await self.agent._aidentity()
        invoker = await sync_to_async(self.agent.invoker)()
        await invoker.adelete(self.conversation_id, user=user, request=request)


# -- @prompt ------------------------------------------------------------------------------


def prompt(
    template: str | None = None,
    *,
    template_name: str | None = None,
    result: type[Any] | None = None,
) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    """Turn a stub method into an agent call.

    The template (inline or ``template_name``) is rendered with the method arguments plus
    ``agent``, ``user``, ``request``, and ``conversation_id``. The return annotation (or
    ``result``) selects ``str``, a Pydantic model, or ``CopilotReply``. Passing
    ``conversation_id=`` makes the call stateful; otherwise each call is one-shot.
    """
    if (template is None) == (template_name is None):
        raise TypeError("@prompt needs exactly one of a template string or template_name")

    def decorate(fn: Callable[..., Any]) -> Callable[..., Any]:
        signature = inspect.signature(fn)
        own_conversation = "conversation_id" in signature.parameters
        result_type = result
        if result_type is None:
            annotation = get_type_hints(fn).get("return")
            result_type = None if annotation in (None, type(None), str) else annotation

        def prepare(self: CopilotAgent, args: tuple[Any, ...], kwargs: dict[str, Any]) -> tuple[dict[str, Any], str | None]:
            conversation_id = None if own_conversation else kwargs.pop("conversation_id", None)
            bound = signature.bind(self, *args, **kwargs)
            bound.apply_defaults()
            values = dict(bound.arguments)
            values.pop(next(iter(signature.parameters)))
            if own_conversation:
                conversation_id = values.get("conversation_id")
            return values, conversation_id

        def render(values: dict[str, Any]) -> str:
            if template is not None:
                return templating.render_string(template, values)
            return templating.render_template(template_name, values)  # type: ignore[arg-type]

        if inspect.iscoroutinefunction(fn):

            @functools.wraps(fn)
            async def amethod(self: CopilotAgent, *args: Any, **kwargs: Any) -> Any:
                values, conversation_id = prepare(self, args, kwargs)
                user, request = await self._aidentity()
                context = {"agent": self, "user": user, "request": request, "conversation_id": conversation_id, **values}
                text = await sync_to_async(render)(context)
                return await self.aask(text, result=result_type, conversation_id=conversation_id)

            method: Callable[..., Any] = amethod
        else:

            @functools.wraps(fn)
            def smethod(self: CopilotAgent, *args: Any, **kwargs: Any) -> Any:
                values, conversation_id = prepare(self, args, kwargs)
                user, request = self._identity()
                context = {"agent": self, "user": user, "request": request, "conversation_id": conversation_id, **values}
                return self.ask(render(context), result=result_type, conversation_id=conversation_id)

            method = smethod

        method.__copilot_prompt__ = template or template_name  # type: ignore[attr-defined]
        return method

    return decorate


# -- definition building ----------------------------------------------------------------


class _Builder:
    def __init__(self, cls: type[CopilotAgent]) -> None:
        self.cls = cls
        self.settings = get_settings()
        self.overrides = self.settings.agent_overrides(cls.name)
        self.issues: list[ConfigIssue] = []

    def pick(self, key: str, attr: str, default: Any = None) -> Any:
        if key in self.overrides:
            return self.overrides[key]
        value = getattr(self.cls, attr, None)
        return default if value is None else value

    def problem(self, message: str, kind: str = "reference") -> None:
        self.issues.append(ConfigIssue(kind, f"Copilot agent {self.cls.name!r}: {message}"))

    def load(self, value: Any, what: str) -> Any:
        if isinstance(value, str):
            try:
                return import_string(value)
            except ImportError as exc:
                self.problem(f"cannot import {what} {value!r} ({exc})")
                return None
        return value

    def tools(self) -> list[CopilotTool]:
        resolved = []
        for item in self.pick("TOOLS", "tools", ()) or ():
            if isinstance(item, str) and item in registry.tools:
                resolved.append(registry.tools[item])
                continue
            if isinstance(item, str) and "." not in item:
                self.problem(f"unknown tool {item!r}")
                continue
            loaded = self.load(item, "tool")
            if loaded is None:
                continue
            try:
                resolved.append(as_tool(loaded))
            except CopilotConfigurationError as exc:
                self.problem(str(exc))
        return resolved

    def mcp_servers(self) -> dict[str, Any]:
        value = self.pick("MCP_SERVERS", "mcp_servers", ()) or ()
        if isinstance(value, Mapping):
            return dict(value)
        servers: dict[str, Any] = {}
        known = self.settings.MCP_SERVERS or {}
        for name in value:
            if name in known:
                servers[name] = dict(known[name])
            else:
                self.problem(f"unknown MCP server {name!r}; define it in COPILOT['MCP_SERVERS']")
        return servers

    def provider(self) -> Mapping[str, Any] | None:
        value = self.pick("PROVIDER", "provider")
        if isinstance(value, str):
            known = self.settings.PROVIDERS or {}
            if value not in known:
                self.problem(f"unknown provider {value!r}; define it in COPILOT['PROVIDERS']")
                return None
            return dict(known[value])
        return value

    def instance(self, value: Any, what: str) -> Any:
        loaded = self.load(value, what)
        return loaded() if inspect.isclass(loaded) else loaded

    def hooks(self) -> list[CopilotAgentHooks]:
        result = []
        for item in [*(self.settings.HOOKS or ()), *(self.pick("HOOKS", "hooks", ()) or ())]:
            hook = self.instance(item, "hook")
            if hook is None:
                continue
            if not isinstance(hook, CopilotAgentHooks):
                self.problem(f"hook {item!r} is not a CopilotAgentHooks")
                continue
            result.append(hook)
        return result

    def callables(self, attr: str, what: str) -> list[Any]:
        result = []
        for item in getattr(self.cls, attr, None) or ():
            loaded = self.load(item, what)
            if loaded is not None:
                result.append(loaded)
        return result

    def permissions(self) -> Any:
        value = self.pick("PERMISSIONS", "permissions", self.settings.DEFAULTS["PERMISSIONS"])
        if isinstance(value, str) and "." in value:
            value = self.load(value, "permission policy")
        try:
            return policies.as_policy(value)
        except CopilotConfigurationError as exc:
            self.problem(str(exc), "invalid")
            return policies.deny_all()

    def token_resolver(self) -> Any:
        value = self.pick("TOKEN_RESOLVER", "token_resolver", self.settings.AUTH.get("TOKEN_RESOLVER"))
        return None if value is None else self.instance(value, "token resolver")

    def instructions(self) -> str | None:
        text = self.pick("INSTRUCTIONS", "instructions")
        name = self.cls.instructions_template
        if text is None and name:
            try:
                text = templating.render_template(name, {"agent": self.cls, "settings": django_settings})
            except Exception as exc:  # noqa: BLE001 - reported as a configuration problem
                self.problem(f"cannot render instructions_template {name!r} ({exc})")
        return text

    def working_directory(self) -> tuple[Any, Any]:
        value = self.pick("WORKING_DIRECTORY", "working_directory", self.settings.DEFAULTS["WORKING_DIRECTORY"])
        if value != "isolated":
            return (str(value) if isinstance(value, Path) else value), None
        root = self.settings.base_directory / "workspaces"

        def isolated(ctx: AgentContext) -> str:
            path = root / ctx.session_id
            path.mkdir(parents=True, exist_ok=True)
            return str(path)

        def release(ctx: AgentContext) -> None:
            shutil.rmtree(root / ctx.session_id, ignore_errors=True)

        return isolated, release

    def build(self) -> AgentDefinition:
        cls = self.cls
        tools = self.tools()
        working_directory, release = self.working_directory()
        kwargs: dict[str, Any] = {
            "name": cls.name,
            "type_name": f"{cls.__module__}.{cls.__qualname__}",
            "instructions": self.instructions(),
            "instructions_mode": self.pick("INSTRUCTIONS_MODE", "instructions_mode", "append"),
            "system_message": cls.system_message,
            "model": self.pick("MODEL", "model", self.settings.DEFAULTS["MODEL"]),
            "reasoning_effort": self.pick("REASONING_EFFORT", "reasoning_effort", self.settings.DEFAULTS["REASONING_EFFORT"]),
            "provider": self.provider(),
            "tools": tools,
            "mcp_servers": self.mcp_servers(),
            "available_tools": self.pick("AVAILABLE_TOOLS", "available_tools"),
            "excluded_tools": self.pick("EXCLUDED_TOOLS", "excluded_tools", ()),
            "excluded_built_in_agents": self.pick("EXCLUDED_BUILT_IN_AGENTS", "excluded_built_in_agents", ()),
            "built_in_tools": self.pick("BUILT_IN_TOOLS", "built_in_tools", True),
            "tool_search": self.pick("TOOL_SEARCH", "tool_search"),
            "working_directory": working_directory,
            "release_working_directory": release,
            "additional_directories": self.pick("ADDITIONAL_DIRECTORIES", "additional_directories", ()),
            "permissions": self.permissions(),
            "hooks": self.hooks(),
            "listeners": self.callables("listeners", "listener"),
            "customizers": self.callables("customizers", "customizer"),
            "token_resolver": self.token_resolver(),
            "timeout": self.pick("TIMEOUT", "timeout", self.settings.RUNTIME["REQUEST_TIMEOUT"]),
            "expose_tool_errors": bool(getattr(django_settings, "DEBUG", False)),
        }
        if self.issues:
            raise CopilotConfigurationError("; ".join(map(str, self.issues)), self.issues)
        return AgentDefinition(**kwargs)


def build_definition(cls: type[CopilotAgent]) -> AgentDefinition:
    """Resolve settings, class attributes, and defaults into an :class:`AgentDefinition`."""
    return _Builder(cls).build()


def register(cls: type[CopilotAgent]) -> type[CopilotAgent]:
    """Explicitly register an agent class (for agents defined outside ``copilot_agents.py``)."""
    registry.register_agent(cls)
    return cls
