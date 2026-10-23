"""Process-wide Copilot runtime: one asyncio loop thread and one SDK client.

The SDK is asyncio-only and its client is bound to the loop that started it, while most
callers (WSGI views, task workers, commands) are synchronous. ``CopilotRuntime`` owns a
daemon thread running a dedicated event loop and creates and starts the client on that
loop, lazily, on first use. Sync callers block on ``run_coroutine_threadsafe`` futures;
async callers await them with ``asyncio.wrap_future``.

The runtime is fork-safe (a forked child discards inherited state and starts its own loop
on first use) and stops the client at interpreter exit.
"""

from __future__ import annotations

import asyncio
import atexit
import concurrent.futures
import logging
import os
import threading
import weakref
from collections.abc import Awaitable, Callable, Coroutine
from typing import Any, TypeVar

from .activity import SessionActivity
from .errors import CopilotTimeout

log = logging.getLogger("copilot_agents.runtime")

T = TypeVar("T")

ClientFactory = Callable[[], Any]
"""Returns a new, unstarted ``CopilotClient`` (or a compatible fake)."""


class CopilotRuntime:
    """Owns the Copilot loop thread and the SDK client for this process."""

    _instances: weakref.WeakSet[CopilotRuntime] = weakref.WeakSet()

    def __init__(
        self,
        client_factory: ClientFactory,
        *,
        name: str = "copilot",
        shutdown_timeout: float = 10.0,
    ) -> None:
        self.name = name
        self.shutdown_timeout = shutdown_timeout
        self._client_factory = client_factory
        self._lock = threading.Lock()
        self._reset()
        CopilotRuntime._instances.add(self)

    def _reset(self) -> None:
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread: threading.Thread | None = None
        self._client: Any = None
        self._client_lock: asyncio.Lock | None = None
        self._status = "stopped"
        self._failure: str | None = None
        self._pid = os.getpid()
        self.activity = SessionActivity()
        """Sessions with a turn in flight (used on the Copilot loop thread)."""

    # -- status -----------------------------------------------------------------------

    def status(self) -> str:
        """``stopped``, ``starting``, ``ready``, or ``failed(<reason>)``."""
        if self._pid != os.getpid():
            return "stopped"
        if self._status == "failed":
            return f"failed({self._failure})"
        return self._status

    @property
    def client_factory(self) -> ClientFactory:
        return self._client_factory

    def set_client_factory(self, factory: ClientFactory) -> None:
        """Replace the client factory, stopping any running client first."""
        self.shutdown()
        self._client_factory = factory

    def in_loop_thread(self) -> bool:
        thread = self._thread
        return thread is not None and thread.ident == threading.get_ident()

    # -- loop management --------------------------------------------------------------

    def _ensure_loop(self) -> asyncio.AbstractEventLoop:
        with self._lock:
            if self._pid != os.getpid():
                self._reset()
            if self._loop is None:
                loop = asyncio.new_event_loop()
                started = threading.Event()

                def run() -> None:
                    asyncio.set_event_loop(loop)
                    loop.call_soon(started.set)
                    try:
                        loop.run_forever()
                    finally:
                        try:
                            loop.run_until_complete(loop.shutdown_asyncgens())
                        finally:
                            loop.close()

                thread = threading.Thread(target=run, name=f"{self.name}-loop", daemon=True)
                thread.start()
                started.wait()
                self._loop, self._thread = loop, thread
                self._client_lock = None
            return self._loop

    async def client(self) -> Any:
        """The started client; must be awaited on the Copilot loop."""
        if self._client is not None:
            return self._client
        if self._client_lock is None:
            self._client_lock = asyncio.Lock()
        async with self._client_lock:
            if self._client is None:
                self._status = "starting"
                try:
                    client = self._client_factory()
                    await client.start()
                except BaseException as exc:
                    self._status, self._failure = "failed", str(exc) or type(exc).__name__
                    raise
                self._client, self._status, self._failure = client, "ready", None
            return self._client

    # -- calling into the loop --------------------------------------------------------

    def submit(self, coro: Coroutine[Any, Any, T]) -> concurrent.futures.Future[T]:
        """Schedule ``coro`` on the Copilot loop from any thread."""
        if self.in_loop_thread():
            coro.close()
            raise RuntimeError(
                "Blocking Copilot calls cannot be made from the Copilot loop thread; "
                "use the async API from async tools and hooks"
            )
        return asyncio.run_coroutine_threadsafe(coro, self._ensure_loop())

    def run_sync(self, coro: Coroutine[Any, Any, T], timeout: float | None = None) -> T:
        """Run ``coro`` on the Copilot loop and block for its result."""
        future = self.submit(coro)
        try:
            return future.result(timeout)
        except concurrent.futures.TimeoutError:
            future.cancel()
            raise CopilotTimeout(f"Copilot call did not finish within {timeout} seconds") from None

    async def run_async(self, coro: Coroutine[Any, Any, T]) -> T:
        """Run ``coro`` on the Copilot loop and await it from another event loop."""
        return await asyncio.wrap_future(self.submit(coro))

    def call(self, fn: Callable[[Any], Awaitable[T]], timeout: float | None = None) -> T:
        """Run ``fn(client)`` on the Copilot loop and block for its result."""

        async def run() -> T:
            return await fn(await self.client())

        return self.run_sync(run(), timeout)

    # -- shutdown ---------------------------------------------------------------------

    def shutdown(self, timeout: float | None = None) -> None:
        """Stop the client and the loop thread; the next call starts them again."""
        timeout = self.shutdown_timeout if timeout is None else timeout
        with self._lock:
            if self._pid != os.getpid() or self._loop is None:
                self._reset()
                return
            loop, thread, client = self._loop, self._thread, self._client
            self._reset()
        if client is not None:
            try:
                asyncio.run_coroutine_threadsafe(_stop_client(client, timeout), loop).result(
                    timeout + 1
                )
            except Exception:  # noqa: BLE001 - best effort at shutdown
                log.warning("Failed to stop the Copilot client cleanly", exc_info=True)
        loop.call_soon_threadsafe(loop.stop)
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout)


async def _stop_client(client: Any, timeout: float) -> None:
    try:
        await asyncio.wait_for(client.stop(), timeout)
    except Exception:  # noqa: BLE001 - escalate to a forced stop
        force_stop = getattr(client, "force_stop", None)
        if force_stop is not None:
            await force_stop()


def _after_fork_in_child() -> None:
    for runtime in list(CopilotRuntime._instances):
        runtime._lock = threading.Lock()
        runtime._reset()


def _shutdown_all() -> None:
    for runtime in list(CopilotRuntime._instances):
        try:
            runtime.shutdown()
        except Exception:  # noqa: BLE001 - interpreter is exiting
            log.debug("Copilot runtime shutdown failed", exc_info=True)


if hasattr(os, "register_at_fork"):
    os.register_at_fork(after_in_child=_after_fork_in_child)
atexit.register(_shutdown_all)
