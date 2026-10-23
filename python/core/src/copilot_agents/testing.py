"""Scripted, in-memory Copilot runtime for tests (counterpart of ``CopilotTestRuntime``).

The fake client never starts the Copilot runtime and keeps sessions in memory (so
conversations resume), answers prompts from per-agent scripts, runs scripted tool calls
through the session's real permission handler, hooks, and tool handlers, and records
prompts and tool calls::

    runtime = CopilotTestRuntime()
    runtime.agent("support").calls_tool("lookup_order", {"order_id": 42}).replies("Shipped")
    with runtime.install(copilot_runtime):
        ...
    assert runtime.prompts("support") == ["Where is order 42?"]
"""

from __future__ import annotations

import inspect
import json
import threading
import uuid
from collections import defaultdict, deque
from collections.abc import Callable, Iterator, Mapping
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

from copilot import ToolInvocation, ToolResult
from copilot.generated.session_events import (
    AssistantMessageData,
    PermissionRequestCustomTool,
    SessionEvent,
    SessionEventType,
    ToolExecutionCompleteData,
    ToolExecutionStartData,
)
from pydantic import BaseModel

from .context import current_invocation
from .message_sources import to_str as to_wire
from .runtime import CopilotRuntime

__all__ = ["AgentScript", "CopilotTestRuntime", "RecordedToolCall"]


@dataclass(frozen=True)
class _Reply:
    text: str


@dataclass(frozen=True)
class _ToolStep:
    name: str
    arguments: Mapping[str, Any]


@dataclass(frozen=True)
class _Failure:
    message: str


@dataclass(frozen=True)
class RecordedToolCall:
    agent: str
    name: str
    arguments: Mapping[str, Any]
    approved: bool
    result: ToolResult | None = None

    @property
    def ok(self) -> bool:
        return self.approved and self.result is not None and self.result.result_type == "success"

    @property
    def text(self) -> str | None:
        return None if self.result is None else self.result.text_result_for_llm


class AgentScript:
    """Ordered steps for one agent. Each prompt consumes tool calls up to the next reply."""

    def __init__(self, name: str) -> None:
        self.name = name
        self._steps: deque[Any] = deque()
        self._lock = threading.Lock()

    def _add(self, step: Any) -> AgentScript:
        with self._lock:
            self._steps.append(step)
        return self

    def replies(self, *texts: str) -> AgentScript:
        """Queue final replies, one per prompt."""
        for text in texts:
            self._add(_Reply(text))
        return self

    def replies_json(self, value: Any) -> AgentScript:
        """Queue a structured reply (a dict, list, or Pydantic model)."""
        if isinstance(value, BaseModel):
            return self._add(_Reply(value.model_dump_json(by_alias=True)))
        return self._add(_Reply(json.dumps(value)))

    def calls_tool(self, name: str, arguments: Mapping[str, Any] | None = None) -> AgentScript:
        """Queue a tool call that runs before the next reply."""
        return self._add(_ToolStep(name, dict(arguments or {})))

    def fails(self, message: str = "Scripted failure") -> AgentScript:
        """Make the next prompt fail with an error."""
        return self._add(_Failure(message))

    def _next_turn(self) -> list[Any]:
        turn: list[Any] = []
        with self._lock:
            while self._steps:
                step = self._steps.popleft()
                turn.append(step)
                if not isinstance(step, _ToolStep):
                    break
        return turn

    @property
    def pending(self) -> int:
        return len(self._steps)


@dataclass
class _StoredSession:
    session_id: str
    agent: str
    options: dict[str, Any] = field(default_factory=dict)


def _event(event_type: SessionEventType, data: Any) -> SessionEvent:
    return SessionEvent(data=data, id=uuid.uuid4(), timestamp=datetime.now(UTC), type=event_type)


def _filtered_out(name: str, options: Mapping[str, Any]) -> bool:
    available = options.get("available_tools")
    if available is not None:
        return not ({"custom:*", f"custom:{name}"} & set(available))
    return bool({"custom:*", f"custom:{name}"} & set(options.get("excluded_tools") or ()))


async def _maybe_await(value: Any) -> Any:
    return await value if inspect.isawaitable(value) else value


class _FakeSession:
    def __init__(self, runtime: CopilotTestRuntime, stored: _StoredSession) -> None:
        self._runtime = runtime
        self._stored = stored
        self._handlers: list[Callable[[SessionEvent], None]] = []
        self.session_id = stored.session_id
        self.disconnected = False
        on_event = stored.options.get("on_event")
        if on_event is not None:
            self._handlers.append(on_event)

    def on(self, handler: Callable[[SessionEvent], None]) -> Callable[[], None]:
        self._handlers.append(handler)
        return lambda: self._handlers.remove(handler) if handler in self._handlers else None

    def _emit(self, event_type: SessionEventType, data: Any) -> None:
        event = _event(event_type, data)
        for handler in list(self._handlers):
            handler(event)

    async def send_and_wait(self, prompt: str, *, timeout: float = 60.0, **kwargs: Any) -> SessionEvent:
        if self.disconnected:
            raise RuntimeError("Session is disconnected")
        agent = self._stored.agent
        self._runtime._record_prompt(agent, prompt, kwargs.get("source"))
        turn = self._runtime.agent(agent)._next_turn()
        for step in turn:
            if isinstance(step, _ToolStep):
                await self._call_tool(step)
            elif isinstance(step, _Failure):
                raise RuntimeError(step.message)
            else:
                return self._reply(step.text)
        if self._runtime.default_reply is not None:
            return self._reply(self._runtime.default_reply)
        raise AssertionError(f"No scripted reply for Copilot agent {agent!r} (prompt: {prompt!r})")

    def _reply(self, text: str) -> SessionEvent:
        event = _event(
            SessionEventType.ASSISTANT_MESSAGE,
            AssistantMessageData(content=text, message_id=uuid.uuid4().hex),
        )
        for handler in list(self._handlers):
            handler(event)
        return event

    async def _call_tool(self, step: _ToolStep) -> None:
        options = self._stored.options
        agent = self._stored.agent
        tool = next((t for t in options.get("tools") or () if t.name == step.name), None)
        if tool is None or _filtered_out(step.name, options):
            raise AssertionError(f"Copilot agent {agent!r} has no available tool {step.name!r}")
        call_id = f"call_{uuid.uuid4().hex[:12]}"
        hook_input = {
            "sessionId": self.session_id,
            "toolName": step.name,
            "toolArgs": dict(step.arguments),
            "timestamp": int(datetime.now(UTC).timestamp() * 1000),
            "cwd": options.get("working_directory", ""),
        }
        hooks = options.get("hooks") or {}
        approved = True
        if not tool.skip_permission:
            request = PermissionRequestCustomTool(
                tool_description=tool.description or "",
                tool_name=step.name,
                args=dict(step.arguments),
                tool_call_id=call_id,
            )
            decision = await _maybe_await(
                options["on_permission_request"](request, {"session_id": self.session_id})
            )
            approved = str(getattr(decision, "kind", "") or "").startswith("approve")
        if approved and "on_pre_tool_use" in hooks:
            output = await _maybe_await(hooks["on_pre_tool_use"](hook_input, {"session_id": self.session_id}))
            if output and output.get("permissionDecision") == "deny":
                approved = False
        self._emit(
            SessionEventType.TOOL_EXECUTION_START,
            ToolExecutionStartData(tool_call_id=call_id, tool_name=step.name, arguments=dict(step.arguments)),
        )
        result: ToolResult | None = None
        if approved:
            invocation = ToolInvocation(
                session_id=self.session_id,
                tool_call_id=call_id,
                tool_name=step.name,
                arguments=dict(step.arguments),
            )
            result = await _maybe_await(tool.handler(invocation))
            ok = result.result_type == "success"
            key = "on_post_tool_use" if ok else "on_post_tool_use_failure"
            if key in hooks:
                await _maybe_await(
                    hooks[key]({**hook_input, "toolResult": result.text_result_for_llm}, {"session_id": self.session_id})
                )
        else:
            ok = False
        self._emit(
            SessionEventType.TOOL_EXECUTION_COMPLETE,
            ToolExecutionCompleteData(success=ok, tool_call_id=call_id),
        )
        self._runtime._record_tool_call(RecordedToolCall(agent, step.name, dict(step.arguments), approved, result))

    async def send(self, prompt: str, **kwargs: Any) -> str:
        await self.send_and_wait(prompt, **kwargs)
        return uuid.uuid4().hex

    async def abort(self) -> None:
        self._runtime.aborted.append(self.session_id)

    async def disconnect(self) -> None:
        self.disconnected = True


class _FakeClient:
    def __init__(self, runtime: CopilotTestRuntime) -> None:
        self._runtime = runtime
        self.started = False

    async def start(self) -> None:
        self.started = True

    async def stop(self) -> None:
        self.started = False

    async def force_stop(self) -> None:
        self.started = False

    async def get_auth_status(self) -> Any:
        return SimpleNamespace(is_authenticated=True, auth_type="test", login="copilot-test")

    async def list_models(self) -> list[Any]:
        return [SimpleNamespace(id="test-model", name="Test model")]

    async def get_session_metadata(self, session_id: str) -> Any:
        stored = self._runtime._sessions.get(session_id)
        return None if stored is None else SimpleNamespace(session_id=session_id)

    async def create_session(self, *, session_id: str | None = None, **options: Any) -> _FakeSession:
        if options.get("on_permission_request") is None:
            raise ValueError("on_permission_request is required")
        invocation = current_invocation()
        agent = invocation.agent_name if invocation is not None else "default"
        session_id = session_id or uuid.uuid4().hex
        if session_id in self._runtime._sessions:
            raise RuntimeError(f"Session {session_id} already exists")
        stored = _StoredSession(session_id, agent, options)
        self._runtime._sessions[session_id] = stored
        self._runtime.created.append(session_id)
        self._runtime._history.append((agent, options))
        return _FakeSession(self._runtime, stored)

    async def resume_session(self, session_id: str, **options: Any) -> _FakeSession:
        stored = self._runtime._sessions.get(session_id)
        if stored is None:
            raise RuntimeError(f"Session not found: {session_id}")
        stored.options = options
        self._runtime.resumed.append(session_id)
        self._runtime._history.append((stored.agent, options))
        return _FakeSession(self._runtime, stored)

    async def delete_session(self, session_id: str) -> None:
        self._runtime._sessions.pop(session_id, None)
        self._runtime.deleted.append(session_id)


class CopilotTestRuntime:
    """Scripts replies and tool calls per agent and records what happened."""

    def __init__(self, *, default_reply: str | None = None) -> None:
        self.default_reply = default_reply
        self._scripts: dict[str, AgentScript] = {}
        self._sessions: dict[str, _StoredSession] = {}
        self._prompts: dict[str, list[str]] = defaultdict(list)
        self._sources: dict[str, list[str | None]] = defaultdict(list)
        self._tool_calls: list[RecordedToolCall] = []
        self._lock = threading.Lock()
        self.created: list[str] = []
        self.resumed: list[str] = []
        self.deleted: list[str] = []
        self.aborted: list[str] = []
        self._history: list[tuple[str, dict[str, Any]]] = []

    def agent(self, name: str) -> AgentScript:
        """The script for an agent, created on first use."""
        with self._lock:
            return self._scripts.setdefault(name, AgentScript(name))

    def client_factory(self) -> _FakeClient:
        return _FakeClient(self)

    def prompts(self, agent: str) -> list[str]:
        return list(self._prompts.get(agent, ()))

    def sources(self, agent: str) -> list[str | None]:
        """Wire-form message source of each prompt (``None`` when not set), aligned with :meth:`prompts`."""
        return list(self._sources.get(agent, ()))

    def tool_calls(self, agent: str | None = None) -> list[RecordedToolCall]:
        return [call for call in self._tool_calls if agent is None or call.agent == agent]

    def sessions(self, agent: str | None = None) -> list[str]:
        """IDs of sessions that currently exist (ephemeral ones are deleted after use)."""
        return [sid for sid, s in self._sessions.items() if agent is None or s.agent == agent]

    def session_options(self, session_id: str) -> dict[str, Any]:
        """The SDK options a session was last created or resumed with."""
        return dict(self._sessions[session_id].options)

    def last_session_options(self, agent: str) -> dict[str, Any]:
        """The SDK options of the agent's most recent create or resume (even if since deleted)."""
        for name, options in reversed(self._history):
            if name == agent:
                return dict(options)
        raise LookupError(f"No session for agent {agent!r}")

    def reset(self) -> None:
        with self._lock:
            self._scripts.clear()
            self._sessions.clear()
            self._prompts.clear()
            self._sources.clear()
            self._tool_calls.clear()
            for log in (self.created, self.resumed, self.deleted, self.aborted, self._history):
                log.clear()

    @contextmanager
    def install(self, runtime: CopilotRuntime) -> Iterator[CopilotTestRuntime]:
        """Point ``runtime`` at this fake for the duration of the block."""
        previous = runtime.client_factory
        runtime.set_client_factory(self.client_factory)
        try:
            yield self
        finally:
            runtime.set_client_factory(previous)

    def _record_prompt(self, agent: str, prompt: str, source: Any = None) -> None:
        with self._lock:
            self._prompts[agent].append(prompt)
            self._sources[agent].append(to_wire(source))

    def _record_tool_call(self, call: RecordedToolCall) -> None:
        with self._lock:
            self._tool_calls.append(call)
