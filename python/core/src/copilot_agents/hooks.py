"""Agent hooks (counterpart of ``CopilotAgentHooks`` in java-core), mapped onto SDK ``SessionHooks``.

Subclass :class:`CopilotAgentHooks` and override only the methods you need. Each method
receives the SDK hook input (a dict with camelCase keys) and the :class:`AgentContext`, and
may return an SDK hook output dict. Hooks run in order (global hooks first); the first
non-``None`` output wins. Sync methods run through the invocation's dispatcher, so they
may use the ORM; ``async def`` methods run on the Copilot loop.
"""

from __future__ import annotations

import inspect
from collections.abc import Callable, Sequence
from typing import Any

from .context import AgentContext
from .executor import Dispatcher

HOOK_METHODS = (
    "on_pre_tool_use",
    "on_post_tool_use",
    "on_post_tool_use_failure",
    "on_user_prompt_submitted",
    "on_session_start",
    "on_session_end",
    "on_error_occurred",
    "on_agent_stop",
    "on_subagent_start",
    "on_subagent_stop",
)


class CopilotAgentHooks:
    """No-op base class; override the hooks you need."""

    def on_pre_tool_use(self, input: dict[str, Any], ctx: AgentContext) -> dict[str, Any] | None:
        return None

    def on_post_tool_use(self, input: dict[str, Any], ctx: AgentContext) -> dict[str, Any] | None:
        return None

    def on_post_tool_use_failure(
        self, input: dict[str, Any], ctx: AgentContext
    ) -> dict[str, Any] | None:
        return None

    def on_user_prompt_submitted(
        self, input: dict[str, Any], ctx: AgentContext
    ) -> dict[str, Any] | None:
        return None

    def on_session_start(self, input: dict[str, Any], ctx: AgentContext) -> dict[str, Any] | None:
        return None

    def on_session_end(self, input: dict[str, Any], ctx: AgentContext) -> dict[str, Any] | None:
        return None

    def on_error_occurred(self, input: dict[str, Any], ctx: AgentContext) -> dict[str, Any] | None:
        return None

    def on_agent_stop(self, input: dict[str, Any], ctx: AgentContext) -> dict[str, Any] | None:
        return None

    def on_subagent_start(self, input: dict[str, Any], ctx: AgentContext) -> dict[str, Any] | None:
        return None

    def on_subagent_stop(self, input: dict[str, Any], ctx: AgentContext) -> dict[str, Any] | None:
        return None


def _overrides(hooks: CopilotAgentHooks, method: str) -> bool:
    return getattr(type(hooks), method, None) is not getattr(CopilotAgentHooks, method)


def session_hooks(
    hooks: Sequence[CopilotAgentHooks],
    agent: Callable[[], AgentContext],
    dispatcher: Callable[[], Dispatcher],
) -> dict[str, Any] | None:
    """Build SDK ``SessionHooks`` that fan out to ``hooks`` in order."""
    result: dict[str, Any] = {}
    for method in HOOK_METHODS:
        bound = [getattr(h, method) for h in hooks if _overrides(h, method)]
        if bound:
            result[method] = _chain(bound, agent, dispatcher)
    return result or None


def _chain(
    bound: Sequence[Callable[..., Any]],
    agent: Callable[[], AgentContext],
    dispatcher: Callable[[], Dispatcher],
) -> Callable[..., Any]:
    async def run(input: dict[str, Any], invocation: Any = None) -> Any:
        ctx = agent()
        for fn in bound:
            if inspect.iscoroutinefunction(fn):
                output = await fn(input, ctx)
            else:
                output = await dispatcher().call(lambda fn=fn: fn(input, ctx))
            if output is not None:
                return output
        return None

    return run
