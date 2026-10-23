"""Caller identity: session partitioning and per-user GitHub tokens."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from copilot_agents import (
    SHARED,
    CallerIdentity,
    CopilotConfigurationError,
    CopilotTokenResolver,
    StaticTokenResolver,
)

__all__ = [
    "SHARED",
    "CallerIdentity",
    "CopilotTokenResolver",
    "StaticTokenResolver",
    "UserKey",
    "UserTokenResolver",
    "default_user_key",
]

UserKey = Callable[[Any], "str | None"]
"""Maps the object returned by your ``current_user`` dependency to a stable partition key."""

_KEY_ATTRIBUTES = ("id", "pk", "sub", "username")


def default_user_key(user: Any) -> str | None:
    """``None`` -> shared; ``str``/``int`` as-is; mappings or objects via ``id``, ``pk``, ``sub``, ``username``."""
    if user is None:
        return None
    if isinstance(user, bool):
        return None
    if isinstance(user, str | int):
        return str(user)
    for attr in _KEY_ATTRIBUTES:
        value = user.get(attr) if isinstance(user, dict) else getattr(user, attr, None)
        if value is not None and value != "":
            return str(value)
    return None


class UserTokenResolver:
    """Base class for per-user GitHub tokens; sessions are partitioned by ``user_key(user)``.

    Subclasses implement :meth:`token_for_user`. Anonymous callers (``user is None``) get
    the ambient identity and shared sessions. Without an explicit ``user_key``, the
    ``Copilot(user_key=...)`` of the app using the resolver applies. A user without a key is
    an error, so sessions are never shared by accident.
    """

    def __init__(self, user_key: UserKey | None = None) -> None:
        self.user_key = user_key

    def resolve(self, identity: CallerIdentity) -> str | None:
        if identity.user is None:
            return None
        return self.token_for_user(identity.user)

    def partition_key(self, identity: CallerIdentity) -> str | None:
        if identity.user is None:
            return SHARED
        key = (self.user_key or default_user_key)(identity.user)
        if key is None:
            raise CopilotConfigurationError(
                f"Copilot agent {identity.agent_name!r}: {type(self).__name__} cannot derive a partition "
                f"key from user {type(identity.user).__name__}; pass user_key=..."
            )
        return key

    def token_for_user(self, user: Any) -> str | None:  # pragma: no cover - abstract
        raise NotImplementedError
