"""Opt-in error-recovery hooks (counterpart of ``CopilotErrorRecoveryHooks`` in java-core).

Sorts tool failures into categories by phrase lists, adds a note to the model's context telling it to keep
going, and retries transient SDK errors. Nothing is enabled by default: add an instance (or the class, for
the defaults) to an agent's or the application's hook list.
"""

from __future__ import annotations

import logging
import threading
from collections.abc import Iterable
from enum import Enum
from typing import Any, Literal

from .context import AgentContext
from .hooks import CopilotAgentHooks

logger = logging.getLogger(__name__)


class ToolResultCategory(str, Enum):
    PERMISSION_DENIED = "permission_denied"
    SHELL_ERROR = "shell_error"
    TOOL_FAILURE = "tool_failure"
    NORMAL = "normal"


class SdkErrorCategory(str, Enum):
    TRANSIENT = "transient"
    CLIENT_ERROR = "client_error"
    NON_RECOVERABLE = "non_recoverable"


DEFAULT_PERMISSION_PHRASES: tuple[str, ...] = (
    "permission denied",
    "access denied",
    "not permitted",
    "operation not allowed",
    "eacces",
    "eperm",
    "403 forbidden",
)
# "exit code" from the cookbook recipe is left out: successful shell output often reports "exit code 0".
DEFAULT_SHELL_ERROR_PHRASES: tuple[str, ...] = (
    "command not found",
    "no such file or directory",
    "errno",
    "traceback",
    "non-zero exit",
)
DEFAULT_TRANSIENT_ERROR_PHRASES: tuple[str, ...] = (
    "timeout",
    "timed out",
    "429",
    "rate limit",
    "502",
    "503",
    "504",
    "econnreset",
    "temporarily unavailable",
)
DEFAULT_CLIENT_ERROR_PHRASES: tuple[str, ...] = ("400", "401", "403", "404", "422")

DEFAULT_PERMISSION_NOTE = (
    "[SYSTEM NOTE: Permission was denied for this specific action. "
    "Continue using alternative approaches.]"
)
DEFAULT_ERROR_NOTE = (
    "[SYSTEM NOTE: This command encountered an error. This does NOT mean you should stop. "
    "Retry with different arguments, try a different tool, or move on.]"
)
DEFAULT_USER_NOTIFICATION = "Error occurred — continuing investigation."

_MAX_TRACKED = 10_000


def _lower(phrases: Iterable[str]) -> tuple[str, ...]:
    if isinstance(phrases, str):
        raise TypeError("phrases must be a list of strings, not a string")
    return tuple(p.strip().lower() for p in phrases if p and p.strip())


def _text(result: Any) -> str:
    if result is None:
        return ""
    if isinstance(result, str):
        return result
    if isinstance(result, dict) and isinstance(result.get("textResultForLlm"), str):
        return result["textResultForLlm"]
    text = getattr(result, "text_result_for_llm", None)
    return text if isinstance(text, str) else str(result)


class ErrorRecoveryHooks(CopilotAgentHooks):
    """Keeps an agent working when tools fail, and retries transient SDK errors.

    * ``on_post_tool_use`` / ``on_post_tool_use_failure``: classify the tool output or error and return an
      ``additionalContext`` note (the tool result is unchanged). After ``max_consecutive_failures``
      consecutive failures of the same tool in a session, stop adding notes so the model may give up
      (``0`` disables the cap). A successful result of the tool resets its count.
    * ``on_error_occurred``: retry transient errors (timeouts, rate limits, 5xx) ``retry_count`` times; for
      other errors return ``unrecoverable_handling`` (``"skip"`` by default, ``None`` for no opinion).

    Hooks run in order and the first non-``None`` output wins, so list this hook after hooks whose output
    must take precedence. Instances are thread-safe and may be shared by every agent.
    """

    def __init__(
        self,
        *,
        permission_phrases: Iterable[str] = DEFAULT_PERMISSION_PHRASES,
        shell_error_phrases: Iterable[str] = DEFAULT_SHELL_ERROR_PHRASES,
        transient_error_phrases: Iterable[str] = DEFAULT_TRANSIENT_ERROR_PHRASES,
        client_error_phrases: Iterable[str] = DEFAULT_CLIENT_ERROR_PHRASES,
        permission_note: str = DEFAULT_PERMISSION_NOTE,
        error_note: str = DEFAULT_ERROR_NOTE,
        max_consecutive_failures: int = 3,
        retry_count: int = 2,
        unrecoverable_handling: Literal["skip", "abort"] | None = "skip",
        user_notification: str | None = DEFAULT_USER_NOTIFICATION,
    ) -> None:
        if max_consecutive_failures < 0:
            raise ValueError("max_consecutive_failures must not be negative")
        if retry_count < 1:
            raise ValueError("retry_count must be at least 1")
        if unrecoverable_handling not in ("skip", "abort", None):
            raise ValueError("unrecoverable_handling must be 'skip', 'abort', or None")
        if not permission_note.strip() or not error_note.strip():
            raise ValueError("notes must not be blank")
        self.permission_phrases = _lower(permission_phrases)
        self.shell_error_phrases = _lower(shell_error_phrases)
        self.transient_error_phrases = _lower(transient_error_phrases)
        self.client_error_phrases = _lower(client_error_phrases)
        self.permission_note = permission_note
        self.error_note = error_note
        self.max_consecutive_failures = max_consecutive_failures
        self.retry_count = retry_count
        self.unrecoverable_handling = unrecoverable_handling
        self.user_notification = user_notification
        self._failures: dict[tuple[str, str], int] = {}
        self._lock = threading.Lock()

    def classify_tool_result(self, text: str | None) -> ToolResultCategory:
        lower = (text or "").lower()
        if any(p in lower for p in self.permission_phrases):
            return ToolResultCategory.PERMISSION_DENIED
        if any(p in lower for p in self.shell_error_phrases):
            return ToolResultCategory.SHELL_ERROR
        return ToolResultCategory.NORMAL

    def classify_sdk_error(self, error: str | None, recoverable: bool = False) -> SdkErrorCategory:
        lower = (error or "").lower()
        if any(p in lower for p in self.transient_error_phrases):
            return SdkErrorCategory.TRANSIENT
        if any(p in lower for p in self.client_error_phrases):
            return SdkErrorCategory.CLIENT_ERROR
        return SdkErrorCategory.TRANSIENT if recoverable else SdkErrorCategory.NON_RECOVERABLE

    def on_post_tool_use(self, input: dict[str, Any], ctx: AgentContext) -> dict[str, Any] | None:
        session, tool = self._key(input, ctx)
        category = self.classify_tool_result(_text(input.get("toolResult")))
        if category is ToolResultCategory.NORMAL:
            with self._lock:
                self._failures.pop((session, tool), None)
            return None
        note = self._note(session, tool, category)
        return {"additionalContext": note} if note else None

    def on_post_tool_use_failure(
        self, input: dict[str, Any], ctx: AgentContext
    ) -> dict[str, Any] | None:
        session, tool = self._key(input, ctx)
        category = self.classify_tool_result(str(input.get("error") or ""))
        if category is ToolResultCategory.NORMAL:
            category = ToolResultCategory.TOOL_FAILURE
        note = self._note(session, tool, category)
        return {"additionalContext": note} if note else None

    def on_error_occurred(self, input: dict[str, Any], ctx: AgentContext) -> dict[str, Any] | None:
        category = self.classify_sdk_error(str(input.get("error") or ""), bool(input.get("recoverable")))
        logger.debug("SDK error (%s, %s): %s", category.value, input.get("errorContext"), input.get("error"))
        if category is SdkErrorCategory.TRANSIENT:
            return {"errorHandling": "retry", "retryCount": self.retry_count}
        if self.unrecoverable_handling is None:
            return None
        output: dict[str, Any] = {"errorHandling": self.unrecoverable_handling}
        if self.user_notification:
            output["userNotification"] = self.user_notification
        return output

    def on_session_end(self, input: dict[str, Any], ctx: AgentContext) -> dict[str, Any] | None:
        session = str(input.get("sessionId") or ctx.session_id or "")
        with self._lock:
            for key in [k for k in self._failures if k[0] == session]:
                del self._failures[key]
        return None

    def _key(self, input: dict[str, Any], ctx: AgentContext) -> tuple[str, str]:
        return str(input.get("sessionId") or ctx.session_id or ""), str(input.get("toolName") or "")

    def _note(self, session: str, tool: str, category: ToolResultCategory) -> str | None:
        with self._lock:
            if len(self._failures) >= _MAX_TRACKED:
                self._failures.clear()
            count = self._failures.get((session, tool), 0) + 1
            self._failures[(session, tool)] = count
        if self.max_consecutive_failures and count > self.max_consecutive_failures:
            logger.debug("Tool %s failed %d times in a row (%s); not nudging", tool, count, category.value)
            return None
        logger.debug("Tool %s failed (%s, attempt %d)", tool, category.value, count)
        if category is ToolResultCategory.PERMISSION_DENIED:
            return self.permission_note
        return self.error_note
