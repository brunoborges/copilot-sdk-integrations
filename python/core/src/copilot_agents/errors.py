"""Exceptions raised by the agent layer."""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any


class CopilotAgentError(Exception):
    """Base class for agent-layer errors."""


class CopilotConfigurationError(CopilotAgentError, ValueError):
    """An agent definition or setting is invalid; ``issues`` holds categorized details."""

    def __init__(self, message: str, issues: Sequence[Any] = ()) -> None:
        super().__init__(message)
        self.issues = tuple(issues)


class CopilotConversationNotFound(CopilotAgentError, LookupError):
    """A conversation was resumed but its runtime session does not exist."""

    def __init__(self, agent: str, conversation_id: str) -> None:
        super().__init__(f"Copilot agent {agent!r}: conversation {conversation_id!r} was not found")
        self.agent = agent
        self.conversation_id = conversation_id


class CopilotInvocationError(CopilotAgentError, RuntimeError):
    """The runtime failed to produce a reply."""


class CopilotTimeout(CopilotAgentError, TimeoutError):
    """An invocation did not finish within its timeout."""
