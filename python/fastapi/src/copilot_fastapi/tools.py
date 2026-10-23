"""``@copilot_tool``: core tools plus FastAPI conventions (``HTTPException`` and access guards)."""

from __future__ import annotations

import functools
import inspect
from collections.abc import Callable
from typing import Any

from fastapi import HTTPException

from copilot_agents import AgentContext, CopilotTool, ToolError, current_invocation

PERMISSION_DENIED = "Permission denied: the current user may not use this tool."

Guard = Callable[[AgentContext], Any]
"""Receives the invocation context (``ctx.user``, ``ctx.request``); returns truthy to allow."""


def _translate(exc: HTTPException) -> ToolError | None:
    """4xx errors are meant for the caller, so the model sees their detail; 5xx stay generic."""
    if exc.status_code >= 500:
        return None
    detail = exc.detail if isinstance(exc.detail, str) else None
    return ToolError(detail or f"Request failed with status {exc.status_code}.")


def copilot_tool(
    func: Callable[..., Any] | None = None,
    *,
    name: str | None = None,
    description: str | None = None,
    requires: Guard | None = None,
    skip_permission: bool = False,
    overrides_built_in_tool: bool = False,
) -> Any:
    """Expose a function to Copilot agents.

    ``requires`` is an application-level access check run before the tool, independent of
    the agent's *Copilot* permission policy; a falsy result returns "permission denied" to
    the model. ``HTTPException`` with a 4xx status becomes a tool failure carrying its
    ``detail``, so existing service functions can be reused as tools unchanged.
    """

    def decorate(fn: Callable[..., Any]) -> CopilotTool:
        if inspect.iscoroutinefunction(fn):

            @functools.wraps(fn)
            async def run_async(*args: Any, **kwargs: Any) -> Any:
                if requires is not None:
                    allowed = requires(current_invocation())
                    if inspect.isawaitable(allowed):
                        allowed = await allowed
                    if not allowed:
                        raise ToolError(PERMISSION_DENIED)
                try:
                    return await fn(*args, **kwargs)
                except HTTPException as exc:
                    translated = _translate(exc)
                    if translated is None:
                        raise
                    raise translated from exc

            wrapper: Callable[..., Any] = run_async
        else:
            if requires is not None and inspect.iscoroutinefunction(requires):
                raise TypeError(f"Tool {name or fn.__name__!r}: an async guard needs an async tool")

            @functools.wraps(fn)
            def run_sync(*args: Any, **kwargs: Any) -> Any:
                if requires is not None and not requires(current_invocation()):
                    raise ToolError(PERMISSION_DENIED)
                try:
                    return fn(*args, **kwargs)
                except HTTPException as exc:
                    translated = _translate(exc)
                    if translated is None:
                        raise
                    raise translated from exc

            wrapper = run_sync

        tool = CopilotTool(
            wrapper,
            name=name,
            description=description,
            skip_permission=skip_permission,
            overrides_built_in_tool=overrides_built_in_tool,
        )
        tool.requires = requires  # type: ignore[attr-defined]
        return tool

    return decorate(func) if func is not None else decorate
