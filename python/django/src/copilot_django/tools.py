"""``@copilot_tool``: Django-aware tools (user permissions, transactions, ORM errors)."""

from __future__ import annotations

import functools
import inspect
from collections.abc import Callable, Iterable
from typing import Any

from django.core.exceptions import ObjectDoesNotExist, PermissionDenied
from django.db import transaction

from copilot_agents import CopilotConfigurationError, CopilotTool, ToolError, current_invocation

from . import registry

PERMISSION_DENIED = "Permission denied: the current user may not use this tool."
NOT_FOUND = "Not found."


def _perms(permission_required: str | Iterable[str] | None) -> tuple[str, ...]:
    if permission_required is None:
        return ()
    if isinstance(permission_required, str):
        return (permission_required,)
    return tuple(permission_required)


def _user() -> Any:
    invocation = current_invocation()
    return invocation.user if invocation is not None else None


def _check(user: Any, perms: tuple[str, ...]) -> None:
    if user is None or not user.has_perms(perms):
        raise ToolError(PERMISSION_DENIED)


async def _acheck(user: Any, perms: tuple[str, ...]) -> None:
    if user is None:
        raise ToolError(PERMISSION_DENIED)
    allowed = await user.ahas_perms(perms) if hasattr(user, "ahas_perms") else user.has_perms(perms)
    if not allowed:
        raise ToolError(PERMISSION_DENIED)


def _translate(exc: Exception) -> ToolError | None:
    if isinstance(exc, ObjectDoesNotExist):
        return ToolError(NOT_FOUND)
    if isinstance(exc, PermissionDenied):
        return ToolError(str(exc) or PERMISSION_DENIED)
    return None


def copilot_tool(
    func: Callable[..., Any] | None = None,
    *,
    name: str | None = None,
    description: str | None = None,
    permission_required: str | Iterable[str] | None = None,
    atomic: bool = False,
    using: str | None = None,
    skip_permission: bool = False,
    overrides_built_in_tool: bool = False,
    register: bool = True,
) -> Any:
    """Expose a function to Copilot agents.

    ``permission_required`` checks the invoking user's Django permissions (all must pass);
    ``atomic`` runs the tool in ``transaction.atomic(using=...)`` (a savepoint inside the
    caller's transaction). ``skip_permission`` skips the agent's *Copilot* permission
    policy for this tool and should be reserved for harmless tools.
    """
    perms = _perms(permission_required)

    def decorate(fn: Callable[..., Any]) -> CopilotTool:
        if inspect.iscoroutinefunction(fn):
            if atomic:
                raise CopilotConfigurationError(
                    f"Tool {name or fn.__name__!r}: atomic=True requires a sync function "
                    "(Django transactions are not available in async code)"
                )

            @functools.wraps(fn)
            async def run_async(*args: Any, **kwargs: Any) -> Any:
                if perms:
                    await _acheck(_user(), perms)
                try:
                    return await fn(*args, **kwargs)
                except (ObjectDoesNotExist, PermissionDenied) as exc:
                    raise _translate(exc) from exc  # type: ignore[misc]

            wrapper: Callable[..., Any] = run_async
        else:

            @functools.wraps(fn)
            def run_sync(*args: Any, **kwargs: Any) -> Any:
                if perms:
                    _check(_user(), perms)
                try:
                    if atomic:
                        with transaction.atomic(using=using):
                            return fn(*args, **kwargs)
                    return fn(*args, **kwargs)
                except (ObjectDoesNotExist, PermissionDenied) as exc:
                    raise _translate(exc) from exc  # type: ignore[misc]

            wrapper = run_sync

        tool = CopilotTool(
            wrapper,
            name=name,
            description=description,
            skip_permission=skip_permission,
            overrides_built_in_tool=overrides_built_in_tool,
        )
        tool.permission_required = perms  # type: ignore[attr-defined]
        tool.atomic = atomic  # type: ignore[attr-defined]
        if register:
            registry.register_tool(tool)
        return tool

    return decorate(func) if func is not None else decorate
