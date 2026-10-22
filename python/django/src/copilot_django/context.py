"""The current request, set by :class:`~copilot_django.middleware.CopilotContextMiddleware`."""

from __future__ import annotations

from contextvars import ContextVar
from typing import Any

_request: ContextVar[Any] = ContextVar("copilot_django_request", default=None)


def current_request() -> Any:
    """The request being handled, or ``None`` outside requests."""
    return _request.get()


def user_partition(identity: Any) -> str | None:
    """Session partition for callers without a token resolver: the authenticated user's PK."""
    user = identity.user
    if user is not None and getattr(user, "is_authenticated", False) and getattr(user, "pk", None) is not None:
        return str(user.pk)
    return None
