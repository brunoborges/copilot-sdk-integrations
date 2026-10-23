"""Session store and opt-in cleanup of expired sessions, configured from ``COPILOT["SESSIONS"]``."""

from __future__ import annotations

import logging
import os
import shutil
import threading
from typing import Any

from copilot_agents import SessionCleanup, SessionStore

from . import registry
from .conf import get_settings
from .runtime import get_runtime

log = logging.getLogger("copilot_django.sessions")

__all__ = ["DjangoSessionStore", "get_session_cleanup", "get_session_store"]


class DjangoSessionStore(SessionStore):
    """:class:`~copilot_agents.SessionStore` that also accepts agent classes, instances, and names.

    ``session_id_for(SupportAgent, "ticket-42", user=user)`` derives the session ID the
    agent uses for that conversation and user; an agent instance supplies its own user and
    request when none are given.
    """

    def session_id_for(
        self,
        agent: Any,
        conversation_id: str,
        *,
        user: Any = None,
        request: Any = None,
        partition_key: str | None = None,
    ) -> str:
        from .agents import CopilotAgent

        if isinstance(agent, CopilotAgent) and user is None and request is None:
            user, request = agent._identity()
        return super().session_id_for(
            agent, conversation_id, user=user, request=request, partition_key=partition_key
        )

    def _resolve_agent(self, agent: Any) -> Any:
        from .agents import CopilotAgent

        if isinstance(agent, str):
            agent = registry.get_agent(agent)
        if isinstance(agent, CopilotAgent):
            agent = type(agent)
        if isinstance(agent, type) and issubclass(agent, CopilotAgent):
            return agent.invoker()
        return agent


def _release_workspace(session_id: str) -> None:
    """Remove the session's isolated working directory, if any."""
    shutil.rmtree(get_settings().base_directory / "workspaces" / session_id, ignore_errors=True)


_lock = threading.RLock()
_store: DjangoSessionStore | None = None


def get_session_store() -> DjangoSessionStore:
    """The process-wide session store on the Copilot runtime (rebuilt when settings change)."""
    global _store
    if _store is None:
        with _lock:
            if _store is None:
                _store = DjangoSessionStore(
                    get_runtime(),
                    timeout=get_settings().RUNTIME["REQUEST_TIMEOUT"],
                    on_deleted=_release_workspace,
                )
    return _store


def get_session_cleanup(max_age: Any = None) -> SessionCleanup | None:
    """A cleanup job from ``COPILOT["SESSIONS"]``; ``None`` when no ``MAX_AGE`` is set."""
    conf = get_settings().SESSIONS
    max_age = conf["MAX_AGE"] if max_age is None else max_age
    if not max_age:
        return None
    return SessionCleanup(get_session_store(), max_age, interval=conf["CLEANUP_INTERVAL"])


# -- background cleanup -------------------------------------------------------------------


class _Sweeper:
    """A daemon thread that sweeps every cleanup interval until stopped."""

    def __init__(self, cleanup: SessionCleanup) -> None:
        self.cleanup = cleanup
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self._run, name="copilot-session-cleanup", daemon=True)
        self.pid = os.getpid()

    def _run(self) -> None:
        seconds = self.cleanup.interval.total_seconds()
        while not self.stopped.wait(seconds):
            try:
                self.cleanup.sweep()
            except Exception:  # noqa: BLE001 - retried at the next sweep
                log.warning("Copilot session cleanup failed; retrying at the next sweep", exc_info=True)


_sweeper: _Sweeper | None = None


def start_cleanup() -> bool:
    """Start background cleanup when ``MAX_AGE`` is set and ``BACKGROUND_CLEANUP`` is on.

    Called from ``AppConfig.ready()`` and again in forked worker processes. Sweeps never
    start the runtime; they run only once this process has used Copilot.
    """
    global _sweeper
    with _lock:
        if _sweeper is not None and _sweeper.pid == os.getpid():
            return True
        _sweeper = None
        if not get_settings().SESSIONS["BACKGROUND_CLEANUP"]:
            return False
        cleanup = get_session_cleanup()
        if cleanup is None:
            return False
        _sweeper = _Sweeper(cleanup)
        _sweeper.thread.start()
        return True


def stop_cleanup() -> None:
    global _sweeper
    with _lock:
        sweeper, _sweeper = _sweeper, None
    if sweeper is not None and sweeper.pid == os.getpid():
        sweeper.stopped.set()


def settings_changed() -> None:
    """Rebuild the store and restart background cleanup with the new settings."""
    global _store
    stop_cleanup()
    with _lock:
        _store = None
    start_cleanup()


def _after_fork_in_child() -> None:
    global _lock, _sweeper
    _lock = threading.RLock()
    if _sweeper is not None:
        _sweeper = None
        start_cleanup()


if hasattr(os, "register_at_fork"):
    os.register_at_fork(after_in_child=_after_fork_in_child)
