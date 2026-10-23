"""Invocation engine: opens sessions, sends prompts, and returns typed results.

Mirrors ``CopilotAgentInvoker`` in java-core: session IDs are derived (never taken raw
from callers), conversations are created on first use and resumed afterwards, one-shot
calls use an ephemeral session that is deleted afterwards, and every session is
disconnected when the call ends so state lives only in the runtime.
"""

from __future__ import annotations

import asyncio
import contextvars
import logging
import uuid
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from typing import Any, TypeVar

from copilot.generated.session_events import (
    AssistantMessageData,
    ToolExecutionCompleteData,
    ToolExecutionStartData,
)
from pydantic import BaseModel, ValidationError

from . import context as _context
from .context import AgentContext
from .definition import AgentDefinition
from .errors import (
    CopilotConfigurationError,
    CopilotConversationNotFound,
    CopilotInvocationError,
    CopilotTimeout,
)
from .executor import (
    AsyncCallerDispatcher,
    CallerThreadDispatcher,
    CallerThreadExecutor,
    Dispatcher,
    SyncRunner,
    default_fallback,
)
from .hooks import session_hooks
from .runtime import CopilotRuntime
from .session_ids import SHARED, derive_session_id
from .tokens import CallerIdentity

log = logging.getLogger("copilot_agents.invoker")

M = TypeVar("M", bound=BaseModel)

OUTER_TIMEOUT_MARGIN = 15.0
"""Extra seconds the caller waits beyond the agent timeout, covering session setup."""


@dataclass(frozen=True)
class ToolCall:
    name: str
    call_id: str
    arguments: Any = None
    ok: bool | None = None


@dataclass(frozen=True)
class CopilotReply:
    """Final assistant text plus metadata about the turn."""

    text: str
    session_id: str
    conversation_id: str | None = None
    message_id: str | None = None
    tool_calls: tuple[ToolCall, ...] = ()
    data: Any = field(default=None, repr=False)


@dataclass
class _Binding:
    """Per-call state read by session callbacks."""

    context: AgentContext
    dispatcher: Dispatcher


@dataclass(frozen=True)
class _Call:
    context: AgentContext
    token: str | None


class AgentInvoker:
    """Runs an :class:`AgentDefinition` on a :class:`CopilotRuntime`.

    ``partition_fallback`` supplies a partition key when no token resolver is configured
    (Django uses the authenticated user's primary key). ``sync_runner`` runs sync
    callbacks for async callers on their loop (Django uses ``sync_to_async``).
    """

    def __init__(
        self,
        definition: AgentDefinition,
        runtime: CopilotRuntime,
        *,
        sync_runner: SyncRunner | None = None,
        fallback: Dispatcher | None = None,
        partition_fallback: Callable[[CallerIdentity], str | None] | None = None,
    ) -> None:
        self.definition = definition
        self.runtime = runtime
        self.sync_runner = sync_runner
        self.fallback = fallback or default_fallback()
        self.partition_fallback = partition_fallback

    # -- identity ---------------------------------------------------------------------

    def _resolve(self, identity: CallerIdentity) -> tuple[str | None, str]:
        resolver = self.definition.token_resolver
        if resolver is None:
            partition = self.partition_fallback(identity) if self.partition_fallback else None
            return None, partition or SHARED
        token = resolver.resolve(identity)
        partition = resolver.partition_key(identity)
        if token is not None and partition is None:
            raise CopilotConfigurationError(
                f"Copilot agent {self.definition.name!r}: the token resolver returned a "
                "per-caller token without a partition key; return SHARED to share sessions"
            )
        return token, partition or SHARED

    def _prepare(
        self,
        conversation_id: str | None,
        user: Any,
        request: Any,
        attributes: Mapping[str, Any] | None,
        resolved: tuple[str | None, str],
    ) -> _Call:
        token, partition = resolved
        definition = self.definition
        key = conversation_id if conversation_id is not None else f"ephemeral:{uuid.uuid4().hex}"
        if conversation_id is not None and not conversation_id:
            raise ValueError("conversation_id must be a non-empty string")
        session_id = derive_session_id(definition.type_name, definition.name, key, partition)
        ctx = AgentContext(
            agent_name=definition.name,
            session_id=session_id,
            conversation_id=conversation_id,
            user=user,
            request=request,
            partition_key=partition,
            attributes=dict(attributes or {}),
        )
        return _Call(ctx, token)

    def _context_for(self, conversation_id: str, user: Any, request: Any) -> AgentContext:
        identity = CallerIdentity(self.definition.name, user, request)
        return self._prepare(conversation_id, user, request, None, self._resolve(identity)).context

    def session_id(self, conversation_id: str, *, user: Any = None, request: Any = None) -> str:
        """The derived runtime session ID for a conversation of this caller."""
        return self._context_for(conversation_id, user, request).session_id

    # -- public sync API --------------------------------------------------------------

    def invoke(
        self,
        prompt: str,
        *,
        conversation_id: str | None = None,
        result: type[Any] | None = None,
        user: Any = None,
        request: Any = None,
        attributes: Mapping[str, Any] | None = None,
        require_existing: bool = False,
        timeout: float | None = None,
    ) -> Any:
        """Send ``prompt`` and block for the reply. Sync callbacks run on this thread."""
        _ensure_no_running_loop("invoke", "ainvoke")
        identity = CallerIdentity(self.definition.name, user, request)
        call = self._prepare(conversation_id, user, request, attributes, self._resolve(identity))
        executor = CallerThreadExecutor()
        binding = _Binding(call.context, CallerThreadDispatcher(executor, self.fallback))
        timeout = self.definition.timeout if timeout is None else timeout
        coro = self._run(call, binding, prompt, result, require_existing, timeout)
        return self._wait_sync(executor, self.runtime.submit(coro), timeout)

    def delete(self, conversation_id: str, *, user: Any = None, request: Any = None) -> None:
        """Delete a conversation's runtime session (no error when it does not exist)."""
        ctx = self._context_for(conversation_id, user, request)
        self.runtime.run_sync(self._delete(ctx), self.definition.timeout)

    def exists(self, conversation_id: str, *, user: Any = None, request: Any = None) -> bool:
        session_id = self.session_id(conversation_id, user=user, request=request)
        return self.runtime.run_sync(self._exists(session_id), self.definition.timeout)

    # -- public async API -------------------------------------------------------------

    async def ainvoke(
        self,
        prompt: str,
        *,
        conversation_id: str | None = None,
        result: type[Any] | None = None,
        user: Any = None,
        request: Any = None,
        attributes: Mapping[str, Any] | None = None,
        require_existing: bool = False,
        timeout: float | None = None,
    ) -> Any:
        """Async variant of :meth:`invoke`; awaitable from any event loop."""
        loop = asyncio.get_running_loop()
        dispatcher = AsyncCallerDispatcher(loop, contextvars.copy_context(), self.sync_runner)
        identity = CallerIdentity(self.definition.name, user, request)
        resolved = await dispatcher.sync_runner(lambda: self._resolve(identity))
        call = self._prepare(conversation_id, user, request, attributes, resolved)
        timeout = self.definition.timeout if timeout is None else timeout
        coro = self._run(call, _Binding(call.context, dispatcher), prompt, result, require_existing, timeout)
        try:
            return await asyncio.wait_for(
                self.runtime.run_async(coro), timeout + OUTER_TIMEOUT_MARGIN
            )
        except TimeoutError as exc:
            if isinstance(exc, CopilotTimeout):
                raise
            raise CopilotTimeout(
                f"Copilot agent {self.definition.name!r} did not reply within {timeout} seconds"
            ) from None

    async def adelete(self, conversation_id: str, *, user: Any = None, request: Any = None) -> None:
        runner = self.sync_runner or _default_runner
        ctx = await runner(lambda: self._context_for(conversation_id, user, request))
        await self.runtime.run_async(self._delete(ctx))

    async def aexists(self, conversation_id: str, *, user: Any = None, request: Any = None) -> bool:
        session_id = await self._asession_id(conversation_id, user, request)
        return await self.runtime.run_async(self._exists(session_id))

    async def _asession_id(self, conversation_id: str, user: Any, request: Any) -> str:
        runner = self.sync_runner or _default_runner
        return await runner(lambda: self.session_id(conversation_id, user=user, request=request))

    # -- engine (runs on the Copilot loop) --------------------------------------------

    def _wait_sync(self, executor: CallerThreadExecutor, future: Any, timeout: float) -> Any:
        try:
            return executor.run_until(future, timeout + OUTER_TIMEOUT_MARGIN)
        except TimeoutError:
            if future.done():
                raise
            future.cancel()
            raise CopilotTimeout(
                f"Copilot agent {self.definition.name!r} did not reply within {timeout} seconds"
            ) from None

    async def _delete(self, ctx: AgentContext) -> None:
        client = await self.runtime.client()
        if await client.get_session_metadata(ctx.session_id) is not None:
            await client.delete_session(ctx.session_id)
        await self._release(ctx)

    async def _release(self, ctx: AgentContext) -> None:
        release = self.definition.release_working_directory
        if release is not None:
            loop = asyncio.get_running_loop()
            await _quietly(loop.run_in_executor(None, release, ctx), "release the working directory of")

    async def _exists(self, session_id: str) -> bool:
        client = await self.runtime.client()
        return await client.get_session_metadata(session_id) is not None

    def session_options(self, binding: _Binding, token: str | None) -> dict[str, Any]:
        """SDK ``create_session`` / ``resume_session`` keyword arguments for a call."""
        definition = self.definition
        ctx = binding.context

        def agent() -> AgentContext:
            return binding.context

        def dispatcher() -> Dispatcher:
            return binding.dispatcher

        policy = definition.permissions
        if getattr(policy, "pure", False):
            permission_handler: Any = policy
        else:

            async def permission_handler(request: Any, invocation: Any) -> Any:
                return await binding.dispatcher.call(lambda: policy(request, invocation))

        options: dict[str, Any] = {"on_permission_request": permission_handler}
        if definition.model:
            options["model"] = definition.model
        if definition.reasoning_effort:
            options["reasoning_effort"] = definition.reasoning_effort
        system_message = definition.system_message_config()
        if system_message:
            options["system_message"] = system_message
        if definition.tools:
            options["tools"] = [
                tool.to_sdk_tool(agent, dispatcher, expose_errors=definition.expose_tool_errors)
                for tool in definition.tools
            ]
        if definition.mcp_servers:
            options["mcp_servers"] = {k: dict(v) for k, v in definition.mcp_servers.items()}
        if definition.provider is not None:
            options["provider"] = dict(definition.provider)
        if definition.available_tools:
            options["available_tools"] = list(definition.available_tools)
        if definition.tool_search is not None:
            options["tool_search"] = dict(definition.tool_search)
        excluded = definition.effective_excluded_tools
        if excluded:
            options["excluded_tools"] = list(excluded)
        if definition.excluded_built_in_agents:
            options["excluded_builtin_agents"] = list(definition.excluded_built_in_agents)
        directory = definition.working_directory
        if callable(directory):
            directory = directory(ctx)
        if directory:
            options["working_directory"] = str(directory)
        if definition.additional_directories:
            options["additional_directories"] = list(definition.additional_directories)
        hooks = session_hooks(definition.hooks, agent, dispatcher)
        if hooks:
            options["hooks"] = hooks
        if token is not None:
            options["github_token"] = token
        for customizer in definition.customizers:
            customizer(options, ctx)
        if "on_permission_request" not in options or options["on_permission_request"] is None:
            raise CopilotConfigurationError("Agent customizers must not remove the permission handler")
        return options

    async def _open(self, client: Any, call: _Call, binding: _Binding, require_existing: bool) -> Any:
        ctx = call.context
        options = self.session_options(binding, call.token)
        if not ctx.ephemeral:
            # None is the SDK's not-found result; lookup errors must not create a new conversation.
            if await client.get_session_metadata(ctx.session_id) is not None:
                return await client.resume_session(ctx.session_id, **options)
            if require_existing:
                raise CopilotConversationNotFound(self.definition.name, ctx.conversation_id or "")
        return await client.create_session(session_id=ctx.session_id, **options)

    async def _run(
        self,
        call: _Call,
        binding: _Binding,
        prompt: str,
        result: type[Any] | None,
        require_existing: bool,
        timeout: float,
    ) -> Any:
        ctx = call.context
        token = _context._current.set(ctx)
        try:
            client = await self.runtime.client()
            session = await self._open(client, call, binding, require_existing)
            tool_calls: dict[str, ToolCall] = {}
            unsubscribe = session.on(self._observer(ctx, tool_calls))
            try:
                typed = isinstance(result, type) and issubclass(result, BaseModel)
                kwargs: dict[str, Any] = {"timeout": timeout}
                if typed:
                    kwargs["response_schema"] = result
                try:
                    event = await session.send_and_wait(prompt, **kwargs)
                except asyncio.CancelledError:
                    await _quietly(session.abort(), "abort")
                    raise
                except TimeoutError:
                    await _quietly(session.abort(), "abort")
                    raise CopilotTimeout(
                        f"Copilot agent {self.definition.name!r} did not reply within {timeout} seconds"
                    ) from None
                except Exception as exc:
                    raise CopilotInvocationError(
                        f"Copilot agent {self.definition.name!r} failed: {exc}"
                    ) from exc
            finally:
                unsubscribe()
                await _quietly(session.disconnect(), "disconnect")
                if ctx.ephemeral:
                    await _quietly(client.delete_session(ctx.session_id), "delete")
                    await self._release(ctx)
            return self._result(event, ctx, tuple(tool_calls.values()), result)
        finally:
            _context._current.reset(token)

    def _observer(self, ctx: AgentContext, tool_calls: dict[str, ToolCall]) -> Callable[[Any], None]:
        listeners = self.definition.listeners

        def observe(event: Any) -> None:
            data = getattr(event, "data", None)
            if isinstance(data, ToolExecutionStartData):
                tool_calls[data.tool_call_id] = ToolCall(data.tool_name, data.tool_call_id, data.arguments)
            elif isinstance(data, ToolExecutionCompleteData) and data.tool_call_id in tool_calls:
                started = tool_calls[data.tool_call_id]
                tool_calls[data.tool_call_id] = ToolCall(
                    started.name, started.call_id, started.arguments, data.success
                )
            for listener in listeners:
                try:
                    listener(ctx, event)
                except Exception:  # noqa: BLE001 - listeners must not break the turn
                    log.exception("Copilot agent listener failed (agent %s)", ctx.agent_name)

        return observe

    def _result(
        self,
        event: Any,
        ctx: AgentContext,
        tool_calls: tuple[ToolCall, ...],
        result: type[Any] | None,
    ) -> Any:
        data = getattr(event, "data", None)
        if not isinstance(data, AssistantMessageData):
            raise CopilotInvocationError(
                f"Copilot agent {self.definition.name!r} finished without an assistant reply"
            )
        if result is None or result is str:
            return data.content
        if result is CopilotReply:
            return CopilotReply(
                text=data.content,
                session_id=ctx.session_id,
                conversation_id=ctx.conversation_id,
                message_id=data.message_id,
                tool_calls=tool_calls,
                data=data,
            )
        if isinstance(result, type) and issubclass(result, BaseModel):
            try:
                return result.model_validate_json(data.content, by_alias=True, by_name=False)
            except ValidationError as exc:
                raise CopilotInvocationError(
                    f"Copilot agent {self.definition.name!r} returned a reply that does not "
                    f"match {result.__name__}: {exc}"
                ) from exc
        raise TypeError(f"Unsupported result type {result!r}; use str, CopilotReply, or a Pydantic model")


async def _quietly(awaitable: Any, action: str) -> None:
    try:
        await awaitable
    except Exception:  # noqa: BLE001 - cleanup must not mask the real outcome
        log.warning("Failed to %s a Copilot session", action, exc_info=True)


async def _default_runner(fn: Callable[[], Any]) -> Any:
    return await asyncio.get_running_loop().run_in_executor(None, fn)


def _ensure_no_running_loop(sync_name: str, async_name: str) -> None:
    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return
    raise RuntimeError(
        f"{sync_name}() blocks and cannot be called from a running event loop; "
        f"await {async_name}() instead"
    )
