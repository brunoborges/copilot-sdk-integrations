"""Message source (provenance) labels for prompts sent by agents.

The source tells the runtime whether a prompt came from a user, the system, or another
agent (``agent-<id>``). It is caller-supplied provenance, **not** an authenticated identity:
never make authorization decisions from it. It can still influence runtime behavior such as
auto-approval classification, queueing and steering, and non-user sources persist in session
history, so validate identifiers that cross trust boundaries and never put secrets in them.
``"system"`` labels an automated caller (a scheduler, a background job); it does not create a
system-role message.

Resolution order for each call (first match wins):

1. a :func:`message_source` scope opened in the calling context; inside a tool, only a scope
   opened by the tool itself counts (the outer caller's scope does not flow into tools);
2. ``agent-<calling agent>`` when called from another agent's tool;
3. the agent's ``message_source`` setting;
4. nothing, so the runtime applies its default (``user``).

Automatic inference never produces ``user``.
"""

from __future__ import annotations

import re
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Any

from copilot import AgentMessageSource, MessageSource

from . import context as _context

AGENT_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
"""Allowed agent identifiers: 1-128 letters, digits, ``.``, ``_`` or ``-``, starting alphanumeric."""

USER: MessageSource = "user"
SYSTEM: MessageSource = "system"

_scope: ContextVar[tuple[MessageSource, Any] | None] = ContextVar(
    "copilot_message_source", default=None
)


def is_valid_agent_id(agent_id: object) -> bool:
    return isinstance(agent_id, str) and AGENT_ID_PATTERN.fullmatch(agent_id) is not None


def agent(agent_id: str) -> AgentMessageSource:
    """An ``agent-<agent_id>`` source; raises ``ValueError`` for invalid identifiers."""
    if not is_valid_agent_id(agent_id):
        raise ValueError(
            f"Invalid agent id {agent_id!r}: use 1-128 letters, digits, '.', '_' or '-', "
            "starting with a letter or digit"
        )
    return AgentMessageSource(agent_id)


def parse(value: MessageSource | str | None) -> MessageSource | None:
    """Parse ``user``, ``system`` or ``agent-<id>``; ``None`` or ``""`` mean "not set"."""
    if value is None or value == "":
        return None
    if isinstance(value, AgentMessageSource):
        return agent(value.agent_id)
    if value in (USER, SYSTEM):
        return value  # type: ignore[return-value]
    if isinstance(value, str) and value.startswith("agent-"):
        return agent(value[len("agent-"):])
    raise ValueError(f"Invalid message source {value!r}: use 'user', 'system' or 'agent-<id>'")


def to_str(source: MessageSource | None) -> str | None:
    """The wire form of ``source`` (``agent-<id>`` for agent sources)."""
    if isinstance(source, AgentMessageSource):
        return "agent-" + source.agent_id
    return source


@contextmanager
def message_source(source: MessageSource | str) -> Iterator[MessageSource]:
    """Label every agent call made in this context (sync or async) with ``source``."""
    parsed = parse(source)
    if parsed is None:
        raise ValueError("message_source() needs a source")
    token = _scope.set((parsed, _context._current.get()))
    try:
        yield parsed
    finally:
        _scope.reset(token)


def current_message_source() -> MessageSource | None:
    """The source of the innermost :func:`message_source` scope that applies here."""
    scope = _scope.get()
    if scope is not None and scope[1] is _context._current.get():
        return scope[0]
    return None


def resolve(configured: MessageSource | None) -> MessageSource | None:
    scoped = current_message_source()
    if scoped is not None:
        return scoped
    caller = _context._current.get()
    if caller is not None and is_valid_agent_id(caller.agent_name):
        return AgentMessageSource(caller.agent_name)
    return configured
