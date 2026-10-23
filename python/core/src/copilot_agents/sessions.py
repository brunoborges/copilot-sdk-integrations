"""Session listing, history, deletion, and cleanup of expired sessions.

:class:`SessionStore` reads and deletes the runtime's persisted sessions without resuming
them; :class:`SessionCleanup` periodically deletes sessions this library created that have
not been modified within a maximum age. Both run their SDK calls on the runtime's Copilot
loop, like :class:`~copilot_agents.AgentInvoker`, and offer sync and ``a``-prefixed async
variants.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable, Coroutine
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any, Literal, TypeVar

from copilot.generated.session_events import AssistantMessageData, UserMessageData

from .definition import AgentDefinition
from .invoker import AgentInvoker, _ensure_no_running_loop
from .runtime import CopilotRuntime
from .session_ids import SHARED, derive_session_id

log = logging.getLogger("copilot_agents.sessions")

T = TypeVar("T")

MANAGED_PREFIX = "agent-"
"""Prefix of the session IDs derived by this library (see :func:`derive_session_id`)."""

MIN_CLEANUP_INTERVAL = timedelta(minutes=1)
MAX_CLEANUP_INTERVAL = timedelta(hours=1)

PAGE_SIZE = 1000

SessionDeleted = Callable[[str], None]
"""Called with the session ID after a session is deleted (for example to remove its workspace)."""


@dataclass(frozen=True)
class SessionMessage:
    """A user or assistant message from a session's persisted history."""

    role: Literal["user", "assistant"]
    content: str
    message_id: str | None = None
    timestamp: datetime | None = None
    event: Any = field(default=None, repr=False, compare=False)


class SessionStore:
    """Lists, reads, and deletes the runtime's persisted sessions.

    ::

        store = SessionStore(runtime)
        for session in store.list():
            print(session.session_id, session.modified_time, session.summary)
        session_id = store.session_id_for(invoker, "ticket-42", user=user)
        messages = store.get_messages(session_id)
        store.delete(session_id)

    Metadata objects are the SDK's ``SessionMetadata`` (``session_id``, ``start_time``,
    ``modified_time``, ``is_remote``, ``summary``, ``context``). ``on_deleted`` runs (in a
    worker thread) after a session is deleted by this store or by :class:`SessionCleanup`.
    """

    def __init__(
        self,
        runtime: CopilotRuntime,
        *,
        timeout: float | None = None,
        on_deleted: SessionDeleted | None = None,
    ) -> None:
        self.runtime = runtime
        self.timeout = timeout
        self.on_deleted = on_deleted

    # -- session IDs ------------------------------------------------------------------

    def session_id_for(
        self,
        agent: Any,
        conversation_id: str,
        *,
        user: Any = None,
        request: Any = None,
        partition_key: str | None = None,
    ) -> str:
        """The runtime session ID of a conversation, derived as the invoker derives it.

        ``agent`` is an :class:`AgentInvoker` or :class:`AgentDefinition` (framework
        integrations also accept their agent classes, instances, and names). Without
        ``partition_key`` an invoker resolves the partition from ``user`` and ``request``
        (its token resolver or partition fallback); a definition uses the shared partition.
        """
        if not conversation_id:
            raise ValueError("conversation_id must be a non-empty string")
        target = self._resolve_agent(agent)
        if isinstance(target, AgentInvoker):
            if partition_key is None:
                return target.session_id(conversation_id, user=user, request=request)
            target = target.definition
        if not isinstance(target, AgentDefinition):
            raise TypeError(f"Cannot derive a session ID from {agent!r}")
        return derive_session_id(target.type_name, target.name, conversation_id, partition_key or SHARED)

    def _resolve_agent(self, agent: Any) -> Any:
        """Map framework agent references to an invoker or definition (overridden by integrations)."""
        return agent

    # -- async API --------------------------------------------------------------------

    async def alist(self) -> list[Any]:
        """Local (non-remote) sessions, most recently modified first."""
        return await self._arun(self._list())

    async def aget(self, session_id: str) -> Any | None:
        """The session's metadata, or ``None`` when it does not exist."""
        return await self._arun(self._get(_require(session_id)))

    async def aexists(self, session_id: str) -> bool:
        return await self.aget(session_id) is not None

    async def aget_messages(self, session_id: str) -> list[SessionMessage] | None:
        """User and assistant messages in order, read without resuming the session.

        Sub-agent messages, tool calls, and empty assistant messages are skipped. Returns
        ``None`` when the session does not exist.
        """
        return await self._arun(self._messages(_require(session_id)))

    async def adelete(self, session_id: str) -> bool:
        """Delete the session once in-flight turns end; ``False`` if it did not exist."""
        return await self._arun(self._delete(_require(session_id)))

    # -- sync API ---------------------------------------------------------------------

    def list(self) -> list[Any]:
        return self._run(self._list(), "list", "alist")

    def get(self, session_id: str) -> Any | None:
        return self._run(self._get(_require(session_id)), "get", "aget")

    def exists(self, session_id: str) -> bool:
        return self.get(session_id) is not None

    def get_messages(self, session_id: str) -> list[SessionMessage] | None:
        return self._run(self._messages(_require(session_id)), "get_messages", "aget_messages")

    def delete(self, session_id: str) -> bool:
        return self._run(self._delete(_require(session_id)), "delete", "adelete")

    # -- engine (runs on the Copilot loop) --------------------------------------------

    def _run(self, coro: Coroutine[Any, Any, T], sync_name: str, async_name: str) -> T:
        try:
            _ensure_no_running_loop(sync_name, async_name)
        except RuntimeError:
            coro.close()
            raise
        return self.runtime.run_sync(coro, self.timeout)

    async def _arun(self, coro: Coroutine[Any, Any, T]) -> T:
        if self.timeout is None:
            return await self.runtime.run_async(coro)
        return await asyncio.wait_for(self.runtime.run_async(coro), self.timeout)

    async def _list(self) -> list[Any]:
        client = await self.runtime.client()
        sessions = [s for s in await client.list_sessions() if not getattr(s, "is_remote", False)]
        sessions.sort(key=lambda s: _timestamp(getattr(s, "modified_time", None)) or _EPOCH, reverse=True)
        return sessions

    async def _get(self, session_id: str) -> Any | None:
        client = await self.runtime.client()
        return await client.get_session_metadata(session_id)

    async def _messages(self, session_id: str) -> list[SessionMessage] | None:
        client = await self.runtime.client()
        if await client.get_session_metadata(session_id) is None:
            return None
        return [m for event in await _read_events(client, session_id) if (m := _message(event)) is not None]

    async def _delete(self, session_id: str) -> bool:
        client = await self.runtime.client()
        async with self.runtime.activity.exclusive(session_id):
            if await client.get_session_metadata(session_id) is None:
                return False
            await client.delete_session(session_id)
            await self._deleted(session_id)
            return True

    async def _deleted(self, session_id: str) -> None:
        if self.on_deleted is None:
            return
        try:
            await asyncio.get_running_loop().run_in_executor(None, self.on_deleted, session_id)
        except Exception:  # noqa: BLE001 - the session itself is already gone
            log.warning("Copilot session %s was deleted but its cleanup callback failed", session_id, exc_info=True)


class SessionCleanup:
    """Deletes sessions created by this library that were not modified within ``max_age``.

    Deletion is conservative: only local sessions whose ID starts with ``agent-`` are
    considered, each one's metadata is re-read right before it is deleted (and skipped if
    it was modified meanwhile), sessions with a turn in flight are skipped until the next
    sweep, and a sweep is skipped while the runtime's client has not started, so cleanup
    never starts the runtime on its own. Call :meth:`sweep` / :meth:`asweep` to run once,
    or :meth:`run` to sweep every ``interval`` until cancelled.
    """

    def __init__(
        self,
        store: SessionStore,
        max_age: timedelta | float,
        *,
        interval: timedelta | float | None = None,
        prefix: str = MANAGED_PREFIX,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self.store = store
        self.max_age = to_timedelta(max_age)
        if self.max_age <= timedelta(0):
            raise ValueError("max_age must be positive")
        self.interval = default_interval(self.max_age) if interval is None else to_timedelta(interval)
        if self.interval <= timedelta(0):
            raise ValueError("interval must be positive")
        self.prefix = prefix
        self._clock = clock or (lambda: datetime.now(UTC))

    @property
    def runtime(self) -> CopilotRuntime:
        return self.store.runtime

    def sweep(self, *, start_runtime: bool = False) -> int:
        """Delete expired sessions once and return how many were deleted.

        ``start_runtime=True`` starts the client if needed (for one-off commands).
        """
        coro = self._sweep(start_runtime)
        try:
            _ensure_no_running_loop("sweep", "asweep")
        except RuntimeError:
            coro.close()
            raise
        return self.runtime.run_sync(coro)

    async def asweep(self, *, start_runtime: bool = False) -> int:
        return await self.runtime.run_async(self._sweep(start_runtime))

    async def run(self, *, sleep: Callable[[float], Awaitable[Any]] = asyncio.sleep) -> None:
        """Sweep every :attr:`interval` until cancelled; failures are logged and retried."""
        seconds = self.interval.total_seconds()
        while True:
            await sleep(seconds)
            try:
                await self.asweep()
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - retried at the next sweep
                log.warning("Copilot session cleanup failed; retrying at the next sweep", exc_info=True)

    async def _sweep(self, start_runtime: bool) -> int:
        runtime = self.runtime
        if not start_runtime and runtime.status() != "ready":
            return 0
        client = await runtime.client()
        activity = runtime.activity
        cutoff = self._clock() - self.max_age
        deleted = 0
        for session in await client.list_sessions():
            session_id = getattr(session, "session_id", None)
            if not session_id or not self._expired(session, cutoff):
                continue
            if not activity.try_claim(session_id):
                continue
            try:
                current = await client.get_session_metadata(session_id)
                if current is None or not self._expired(current, cutoff):
                    continue
                await client.delete_session(session_id)
            except Exception:  # noqa: BLE001 - one failure must not abort the sweep
                log.warning("Could not delete expired Copilot session %s", session_id, exc_info=True)
                continue
            finally:
                activity.release(session_id)
            deleted += 1
            await self.store._deleted(session_id)
        if deleted:
            log.info("Deleted %d Copilot sessions not modified since %s", deleted, cutoff.isoformat())
        return deleted

    def _expired(self, session: Any, cutoff: datetime) -> bool:
        if getattr(session, "is_remote", False):
            return False
        if not str(getattr(session, "session_id", "")).startswith(self.prefix):
            return False
        modified = _timestamp(getattr(session, "modified_time", None))
        return modified is not None and modified < cutoff


def default_interval(max_age: timedelta) -> timedelta:
    """Half of ``max_age``, clamped between one minute and one hour."""
    return min(max(max_age / 2, MIN_CLEANUP_INTERVAL), MAX_CLEANUP_INTERVAL)


def to_timedelta(value: timedelta | float | int | str) -> timedelta:
    """A ``timedelta`` from a ``timedelta`` or a number (or numeric string) of seconds."""
    if isinstance(value, timedelta):
        return value
    if isinstance(value, bool):
        raise TypeError("Expected a timedelta or a number of seconds")
    if isinstance(value, int | float):
        return timedelta(seconds=value)
    if isinstance(value, str):
        try:
            return timedelta(seconds=float(value))
        except ValueError:
            pass
    raise TypeError(f"Expected a timedelta or a number of seconds, got {value!r}")


_EPOCH = datetime(1970, 1, 1, tzinfo=UTC)


def _timestamp(value: Any) -> datetime | None:
    """A UTC-aware datetime from the SDK's datetime, an ISO-8601 string, or epoch seconds/ms."""
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, datetime):
        parsed = value
    elif isinstance(value, int | float):
        parsed = datetime.fromtimestamp(value / 1000 if value > 1e11 else value, UTC)
    elif isinstance(value, str):
        try:
            parsed = datetime.fromisoformat(value.strip())
        except ValueError:
            return None
    else:
        return None
    return parsed.replace(tzinfo=UTC) if parsed.tzinfo is None else parsed.astimezone(UTC)


async def _read_events(client: Any, session_id: str) -> list[Any]:
    from copilot.generated.rpc import CursorStatus, SessionsReadPersistedEventsRequest

    events: list[Any] = []
    cursor: str | None = None
    while True:
        page = await client.rpc.sessions.read_persisted_events(
            SessionsReadPersistedEventsRequest(session_id=session_id, cursor=cursor, max=PAGE_SIZE)
        )
        if page.cursor_status == CursorStatus.EXPIRED:
            raise RuntimeError(f"Reading the history of Copilot session {session_id!r} expired before it completed")
        events.extend(page.events)
        if not page.has_more:
            return events
        cursor = page.cursor


def _message(event: Any) -> SessionMessage | None:
    if getattr(event, "agent_id", None):
        return None
    data = getattr(event, "data", None)
    timestamp = getattr(event, "timestamp", None)
    if isinstance(data, UserMessageData):
        return SessionMessage("user", data.content, data.message_id, timestamp, event)
    if isinstance(data, AssistantMessageData) and data.content:
        return SessionMessage("assistant", data.content, data.message_id, timestamp, event)
    return None


def _require(session_id: str) -> str:
    if not session_id or not isinstance(session_id, str):
        raise ValueError("session_id must be a non-empty string")
    return session_id
