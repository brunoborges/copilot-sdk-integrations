"""GitHub identity resolution (counterpart of ``CopilotTokenResolver`` in java-core)."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol, runtime_checkable

from .session_ids import SHARED


@dataclass(frozen=True)
class CallerIdentity:
    """Who is invoking an agent, before a session ID exists."""

    agent_name: str
    user: Any = None
    request: Any = None


@runtime_checkable
class CopilotTokenResolver(Protocol):
    """Supplies a GitHub token and a session partition per invocation.

    ``resolve`` returns ``None`` to use the ambient identity (client token or the
    runtime's signed-in user). ``partition_key`` isolates sessions: per-user resolvers
    must return a stable per-user key; :data:`SHARED` (``""``) shares sessions.
    A resolver that returns a token but no partition key fails the invocation, so
    per-user tokens never share sessions by accident.
    """

    def resolve(self, identity: CallerIdentity) -> str | None: ...

    def partition_key(self, identity: CallerIdentity) -> str | None: ...


class StaticTokenResolver:
    """One configured token for every caller; sessions are shared."""

    def __init__(self, token: str) -> None:
        if not token:
            raise ValueError("StaticTokenResolver needs a non-empty token")
        self._token = token

    def resolve(self, identity: CallerIdentity) -> str | None:
        return self._token

    def partition_key(self, identity: CallerIdentity) -> str | None:
        return SHARED

    def __repr__(self) -> str:
        return "StaticTokenResolver(token=***)"
