"""Per-invocation context shared with tools, hooks, policies, and listeners."""

from __future__ import annotations

from collections.abc import Mapping
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class AgentContext:
    """Identifies one agent invocation."""

    agent_name: str
    session_id: str
    conversation_id: str | None = None
    user: Any = None
    request: Any = None
    partition_key: str = ""
    attributes: Mapping[str, Any] = field(default_factory=dict)

    @property
    def ephemeral(self) -> bool:
        """True for one-shot calls whose session is deleted afterwards."""
        return self.conversation_id is None


_current: ContextVar[AgentContext | None] = ContextVar("copilot_agent_context", default=None)


def current_invocation() -> AgentContext | None:
    """The invocation running on the Copilot loop, or ``None`` outside one."""
    return _current.get()
