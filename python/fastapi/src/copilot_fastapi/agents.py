"""Declarative agents: ``CopilotAgent`` subclasses, ``@prompt`` methods, and conversations."""

from __future__ import annotations

import functools
import importlib
import inspect
import re
import shutil
import string
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import TYPE_CHECKING, Any, ClassVar

from copilot_agents import (
    AgentContext,
    AgentDefinition,
    AgentInvoker,
    ConfigIssue,
    CopilotAgentError,
    CopilotAgentHooks,
    CopilotConfigurationError,
    CopilotConversationNotFound,
    CopilotTool,
    policies,
)
from copilot_agents.tools import as_tool

from .auth import UserTokenResolver

if TYPE_CHECKING:
    from .copilot import Copilot

PROMPT_GLOBALS = frozenset({"agent", "user", "request", "conversation_id"})


class PromptRenderError(CopilotAgentError, ValueError):
    """A ``@prompt`` template references a variable that is not available."""


def default_name(class_name: str) -> str:
    """``SupportAgent`` -> ``support``; ``OrderTriageAgent`` -> ``order_triage``."""
    base = class_name[: -len("Agent")] if class_name.endswith("Agent") and class_name != "Agent" else class_name
    return re.sub(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", "_", base).lower()


class CopilotAgent:
    """Base class for Copilot agents.

    Instances are cheap, per-request handles bound to a :class:`~copilot_fastapi.Copilot`,
    a user, and a request; inject them with ``copilot.depends(MyAgent)``. Overrides in
    ``settings.agents[name]`` replace class attributes, which replace ``settings.defaults``.
    Attributes that name tools, hooks, policies, resolvers, listeners, or customizers accept
    objects or dotted import paths.
    """

    abstract: ClassVar[bool] = True
    name: ClassVar[str] = ""

    instructions: ClassVar[str | None] = None
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
    working_directory: ClassVar[str | Path | Callable[[AgentContext], str | None] | None] = None
    additional_directories: ClassVar[list[str] | None] = None
    permissions: ClassVar[Any] = None
    hooks: ClassVar[list[Any] | None] = None
    listeners: ClassVar[list[Any] | None] = None
    customizers: ClassVar[list[Any] | None] = None
    token_resolver: ClassVar[Any] = None
    timeout: ClassVar[float | None] = None
    message_source: ClassVar[Any] = None
    """``"user"``, ``"system"`` or ``"agent-<id>"`` provenance label (not an identity)."""

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        if "abstract" not in cls.__dict__:
            cls.abstract = False
        if not cls.__dict__.get("name"):
            cls.name = default_name(cls.__name__)

    def __init__(
        self,
        copilot: Copilot,
        *,
        user: Any = None,
        request: Any = None,
        attributes: Mapping[str, Any] | None = None,
    ) -> None:
        """``attributes`` (for example a database session) reach tools as ``ctx.agent.attributes``."""
        if type(self).abstract:
            raise CopilotConfigurationError(f"{type(self).__qualname__} is abstract")
        self.copilot = copilot
        self.user = user
        self.request = request
        self.attributes = dict(attributes or {})

    def __repr__(self) -> str:
        return f"<{type(self).__name__} {self.name!r}>"

    @property
    def definition(self) -> AgentDefinition:
        return self.copilot.definition(type(self))

    @property
    def invoker(self) -> AgentInvoker:
        return self.copilot.invoker(type(self))

    def _attributes(self, extra: Mapping[str, Any] | None) -> dict[str, Any]:
        return {**self.attributes, **(extra or {})}

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
        """Send ``prompt`` and block; use from ``def`` endpoints, scripts, and workers."""
        return self.invoker.invoke(
            prompt,
            conversation_id=conversation_id,
            result=result,
            user=self.user,
            request=self.request,
            attributes=self._attributes(attributes),
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
        """Send ``prompt`` from ``async def`` endpoints; one-shot unless ``conversation_id`` is given."""
        return await self.invoker.ainvoke(
            prompt,
            conversation_id=conversation_id,
            result=result,
            user=self.user,
            request=self.request,
            attributes=self._attributes(attributes),
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
    continued from any request, worker, or process that shares the runtime state.
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
        return self.agent.invoker.session_id(self.conversation_id, user=self.agent.user, request=self.agent.request)

    def send(
        self,
        prompt: str,
        *,
        result: type[Any] | None = None,
        attributes: Mapping[str, Any] | None = None,
        timeout: float | None = None,
    ) -> Any:
        return self.agent.invoker.invoke(
            prompt,
            conversation_id=self.conversation_id,
            result=result,
            user=self.agent.user,
            request=self.agent.request,
            attributes=self.agent._attributes(attributes),
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
        return await self.agent.invoker.ainvoke(
            prompt,
            conversation_id=self.conversation_id,
            result=result,
            user=self.agent.user,
            request=self.agent.request,
            attributes=self.agent._attributes(attributes),
            require_existing=self.require_existing,
            timeout=timeout,
        )

    def exists(self) -> bool:
        return self.agent.invoker.exists(self.conversation_id, user=self.agent.user, request=self.agent.request)

    async def aexists(self) -> bool:
        return await self.agent.invoker.aexists(self.conversation_id, user=self.agent.user, request=self.agent.request)

    def delete(self) -> None:
        """Delete the runtime session and its isolated workspace."""
        self.agent.invoker.delete(self.conversation_id, user=self.agent.user, request=self.agent.request)

    async def adelete(self) -> None:
        await self.agent.invoker.adelete(self.conversation_id, user=self.agent.user, request=self.agent.request)


# -- @prompt ------------------------------------------------------------------------------


class _StrictFormatter(string.Formatter):
    def get_value(self, key: int | str, args: Any, kwargs: Mapping[str, Any]) -> Any:
        if isinstance(key, int):
            raise PromptRenderError("Prompt templates use named fields only, for example {order_id}")
        try:
            return kwargs[key]
        except KeyError:
            raise PromptRenderError(f"Prompt template variable {key!r} is not defined") from None

    def get_field(self, field_name: str, args: Any, kwargs: Mapping[str, Any]) -> Any:
        try:
            return super().get_field(field_name, args, kwargs)
        except PromptRenderError:
            raise
        except (AttributeError, KeyError, IndexError, TypeError) as exc:
            raise PromptRenderError(f"Cannot resolve prompt template field {field_name!r} ({exc})") from exc


_formatter = _StrictFormatter()


def render_prompt(template: str, values: Mapping[str, Any]) -> str:
    """Render a ``str.format``-style template; undefined fields raise :class:`PromptRenderError`."""
    return _formatter.vformat(template, (), values)


def _template_roots(template: str) -> set[str]:
    roots: set[str] = set()
    for _, field, _, _ in _formatter.parse(template):
        if field is None:
            continue
        root = re.split(r"[.\[]", field, maxsplit=1)[0]
        if root == "" or root.isdigit():
            raise PromptRenderError(f"Prompt template {template!r} uses a positional field; name it instead")
        roots.add(root)
    return roots


def _return_type(fn: Callable[..., Any]) -> Any:
    """Resolve only the return annotation (on first call, so later-defined models work)."""
    annotation = inspect.signature(fn).return_annotation
    if isinstance(annotation, str):
        try:
            annotation = eval(annotation, fn.__globals__)  # noqa: S307 - the module's own annotation
        except Exception as exc:  # noqa: BLE001 - reported as a configuration error
            raise CopilotConfigurationError(
                f"@prompt on {fn.__qualname__}: cannot resolve return annotation {annotation!r} ({exc}); "
                "pass @prompt(..., result=Model)"
            ) from exc
    if annotation in (inspect.Signature.empty, None, type(None), str):
        return None
    return annotation


def prompt(template: str, *, result: type[Any] | None = None) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    """Turn a stub method into an agent call.

    The template uses ``str.format`` syntax (``{order_id}``, ``{user.name}``) and is
    rendered with the method arguments plus ``agent``, ``user``, ``request``, and
    ``conversation_id``. Argument values are inserted verbatim, never re-parsed. The return
    annotation (or ``result``) selects ``str``, a Pydantic model, or ``CopilotReply``.
    ``def`` stubs block (use them from ``def`` endpoints); ``async def`` stubs are awaitable.
    Passing ``conversation_id=`` makes the call stateful; otherwise each call is one-shot.
    """

    def decorate(fn: Callable[..., Any]) -> Callable[..., Any]:
        signature = inspect.signature(fn)
        params = list(signature.parameters)[1:]
        unknown = _template_roots(template) - set(params) - PROMPT_GLOBALS
        if unknown:
            raise PromptRenderError(
                f"@prompt on {fn.__qualname__}: template uses undefined variables {sorted(unknown)}"
            )
        own_conversation = "conversation_id" in signature.parameters
        resolved: list[Any] = [] if result is None else [result]

        def result_type() -> Any:
            if not resolved:
                resolved.append(_return_type(fn))
            return resolved[0]

        def prepare(self: CopilotAgent, args: tuple[Any, ...], kwargs: dict[str, Any]) -> tuple[str, str | None]:
            conversation_id = None if own_conversation else kwargs.pop("conversation_id", None)
            bound = signature.bind(self, *args, **kwargs)
            bound.apply_defaults()
            values = dict(bound.arguments)
            values.pop(next(iter(signature.parameters)))
            if own_conversation:
                conversation_id = values.get("conversation_id")
            context = {
                "agent": self,
                "user": self.user,
                "request": self.request,
                "conversation_id": conversation_id,
                **values,
            }
            return render_prompt(template, context), conversation_id

        if inspect.iscoroutinefunction(fn):

            @functools.wraps(fn)
            async def amethod(self: CopilotAgent, *args: Any, **kwargs: Any) -> Any:
                text, conversation_id = prepare(self, args, kwargs)
                return await self.aask(text, result=result_type(), conversation_id=conversation_id)

            method: Callable[..., Any] = amethod
        else:

            @functools.wraps(fn)
            def smethod(self: CopilotAgent, *args: Any, **kwargs: Any) -> Any:
                text, conversation_id = prepare(self, args, kwargs)
                return self.ask(text, result=result_type(), conversation_id=conversation_id)

            method = smethod

        method.__copilot_prompt__ = template  # type: ignore[attr-defined]
        return method

    return decorate


# -- definition building ----------------------------------------------------------------


def import_path(path: str) -> Any:
    """Import ``package.module.attr`` or ``package.module:attr``."""
    module_name, sep, attr = path.partition(":")
    if not sep:
        module_name, _, attr = path.rpartition(".")
    if not module_name or not attr:
        raise ImportError(f"{path!r} is not a dotted path")
    module = importlib.import_module(module_name)
    target: Any = module
    for part in attr.split("."):
        try:
            target = getattr(target, part)
        except AttributeError:
            raise ImportError(f"{module_name!r} has no attribute {attr!r}") from None
    return target


class _Builder:
    def __init__(self, cls: type[CopilotAgent], copilot: Copilot) -> None:
        self.cls = cls
        self.copilot = copilot
        self.settings = copilot.settings
        self.overrides = self.settings.agent_overrides(cls.name)
        self.issues: list[ConfigIssue] = []

    def pick(self, key: str, default: Any = None) -> Any:
        if key in self.overrides:
            value = self.overrides[key]
        else:
            value = getattr(self.cls, key, None)
        return default if value is None else value

    def problem(self, message: str, kind: str = "reference") -> None:
        self.issues.append(ConfigIssue(kind, f"Copilot agent {self.cls.name!r}: {message}"))

    def load(self, value: Any, what: str) -> Any:
        if isinstance(value, str):
            try:
                return import_path(value)
            except (ImportError, ValueError) as exc:
                self.problem(f"cannot import {what} {value!r} ({exc})")
                return None
        return value

    def instance(self, value: Any, what: str) -> Any:
        loaded = self.load(value, what)
        return loaded() if inspect.isclass(loaded) else loaded

    def tools(self) -> list[CopilotTool]:
        resolved = []
        for item in self.pick("tools", ()) or ():
            loaded = self.load(item, "tool")
            if loaded is None:
                continue
            try:
                resolved.append(as_tool(loaded))
            except CopilotConfigurationError as exc:
                self.problem(str(exc))
        return resolved

    def mcp_servers(self) -> dict[str, Any]:
        value = self.pick("mcp_servers", ()) or ()
        if isinstance(value, Mapping):
            return {name: dict(config) for name, config in value.items()}
        servers: dict[str, Any] = {}
        known = self.settings.mcp_servers
        for name in value:
            if name in known:
                servers[name] = dict(known[name])
            else:
                self.problem(f"unknown MCP server {name!r}; define it in settings.mcp_servers")
        return servers

    def provider(self) -> Mapping[str, Any] | None:
        value = self.pick("provider")
        if isinstance(value, str):
            known = self.settings.providers
            if value not in known:
                self.problem(f"unknown provider {value!r}; define it in settings.providers")
                return None
            return dict(known[value])
        return value

    def hooks(self) -> list[CopilotAgentHooks]:
        result = []
        items = [*self.settings.hooks, *self.copilot.hooks, *(self.pick("hooks", ()) or ())]
        for item in items:
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
        value = self.pick("permissions", self.settings.defaults.permissions)
        if isinstance(value, str) and "." in value:
            value = self.load(value, "permission policy")
        if isinstance(value, Mapping):
            value = {str(key).upper(): item for key, item in value.items()}
        try:
            return policies.as_policy(value)
        except CopilotConfigurationError as exc:
            self.problem(str(exc), "invalid")
            return policies.deny_all()

    def token_resolver(self) -> Any:
        value = self.pick("token_resolver")
        if value is None:
            value = self.copilot.token_resolver
        if value is None:
            value = self.settings.auth.token_resolver
        if value is None:
            return None
        resolver = self.instance(value, "token resolver")
        if isinstance(resolver, UserTokenResolver) and resolver.user_key is None:
            resolver.user_key = self.copilot.user_key
        return resolver

    def working_directory(self) -> tuple[Any, Any]:
        value = self.pick("working_directory", self.settings.defaults.working_directory)
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
        defaults = self.settings.defaults
        working_directory, release = self.working_directory()
        kwargs: dict[str, Any] = {
            "name": cls.name,
            "type_name": f"{cls.__module__}.{cls.__qualname__}",
            "instructions": self.pick("instructions"),
            "instructions_mode": self.pick("instructions_mode", "append"),
            "system_message": cls.system_message,
            "model": self.pick("model", defaults.model),
            "reasoning_effort": self.pick("reasoning_effort", defaults.reasoning_effort),
            "provider": self.provider(),
            "tools": self.tools(),
            "mcp_servers": self.mcp_servers(),
            "available_tools": self.pick("available_tools"),
            "excluded_tools": self.pick("excluded_tools", ()),
            "excluded_built_in_agents": self.pick("excluded_built_in_agents", ()),
            "built_in_tools": self.pick("built_in_tools", True),
            "working_directory": working_directory,
            "release_working_directory": release,
            "additional_directories": self.pick("additional_directories", ()),
            "permissions": self.permissions(),
            "hooks": self.hooks(),
            "listeners": self.callables("listeners", "listener"),
            "customizers": self.callables("customizers", "customizer"),
            "token_resolver": self.token_resolver(),
            "timeout": self.pick("timeout", self.settings.runtime.request_timeout),
            "message_source": self.pick("message_source"),
            "expose_tool_errors": self.copilot.expose_tool_errors,
        }
        if self.issues:
            raise CopilotConfigurationError("; ".join(map(str, self.issues)), self.issues)
        return AgentDefinition(**kwargs)


def build_definition(cls: type[CopilotAgent], copilot: Copilot) -> AgentDefinition:
    """Resolve overrides, class attributes, and defaults into an :class:`AgentDefinition`."""
    return _Builder(cls, copilot).build()
