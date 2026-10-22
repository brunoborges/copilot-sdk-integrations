"""The per-process Copilot runtime, configured from ``settings.COPILOT``."""

from __future__ import annotations

import threading
from typing import Any

from django.db import close_old_connections

from copilot_agents import CopilotRuntime
from copilot_agents.executor import ThreadPoolDispatcher

from .conf import get_settings

_lock = threading.Lock()
_runtime: CopilotRuntime | None = None


def _connection(runtime: dict[str, Any]) -> Any:
    from copilot import RuntimeConnection

    kind = runtime["CONNECTION"]
    if kind == "uri":
        return RuntimeConnection.for_uri(runtime["URL"], connection_token=runtime["CONNECTION_TOKEN"])
    if kind == "tcp":
        return RuntimeConnection.for_tcp(
            port=runtime["PORT"] or 0,
            connection_token=runtime["CONNECTION_TOKEN"],
            path=runtime["CLI_PATH"],
        )
    if kind == "inprocess":
        return RuntimeConnection.for_inprocess()
    return RuntimeConnection.for_stdio(path=runtime["CLI_PATH"])


def build_client() -> Any:
    """Create an SDK ``CopilotClient`` from settings (not started)."""
    from copilot import CopilotClient

    conf = get_settings()
    runtime = conf.RUNTIME
    base = conf.base_directory
    base.mkdir(parents=True, exist_ok=True)
    kwargs: dict[str, Any] = {
        "connection": _connection(runtime),
        "base_directory": str(base),
        "log_level": runtime["LOG_LEVEL"],
        "mode": runtime["MODE"],
    }
    if conf.AUTH.get("GITHUB_TOKEN"):
        kwargs["github_token"] = conf.AUTH["GITHUB_TOKEN"]
    if conf.TELEMETRY:
        kwargs["telemetry"] = {str(k).lower(): v for k, v in conf.TELEMETRY.items()}
    return CopilotClient(**kwargs)


def get_runtime() -> CopilotRuntime:
    """The process-wide runtime; the client starts lazily on first use and after fork."""
    global _runtime
    if _runtime is None:
        with _lock:
            if _runtime is None:
                _runtime = CopilotRuntime(build_client, name="copilot-django")
    return _runtime


def start() -> None:
    """Start the client in the background (for ``RUNTIME.START = "eager"`` or post-fork hooks)."""
    runtime = get_runtime()
    runtime.submit(runtime.client())


def settings_changed() -> None:
    """Stop the client so the next call picks up new settings (keeps any test fake installed)."""
    if _runtime is not None:
        _runtime.shutdown()


def _with_fresh_connections(fn: Any) -> Any:
    close_old_connections()
    try:
        return fn()
    finally:
        close_old_connections()


fallback_dispatcher = ThreadPoolDispatcher(wrapper=_with_fresh_connections)
"""Runs sync callbacks that have no waiting caller, with Django connection hygiene."""


async def sync_runner(fn: Any) -> Any:
    """Runs sync callbacks for async callers, following Django's ORM-from-async rule."""
    from asgiref.sync import sync_to_async

    return await sync_to_async(fn, thread_sensitive=True)()
