"""Dispatch of synchronous callbacks from the Copilot loop to the right thread.

The SDK invokes tool handlers, permission handlers, and hooks on the Copilot loop. Sync
user code (often ORM code) must not run there, so each invocation installs a dispatcher:

- :class:`CallerThreadDispatcher` for sync callers: callbacks run on the *calling thread*,
  which services a work queue while it waits for the agent (the technique behind asgiref's
  ``CurrentThreadExecutor``). Tool code therefore sees the caller's thread-locals,
  database connection, and open transaction.
- :class:`AsyncCallerDispatcher` for async callers: callbacks are scheduled on the
  caller's event loop and run through a pluggable sync runner (Django uses
  ``sync_to_async(thread_sensitive=True)``).
- :class:`ThreadPoolDispatcher` when no caller is waiting.
"""

from __future__ import annotations

import asyncio
import concurrent.futures
import contextvars
import queue
import threading
import time
from collections.abc import Awaitable, Callable
from typing import Any, Protocol, TypeVar

T = TypeVar("T")

SyncRunner = Callable[[Callable[[], Any]], Awaitable[Any]]
"""Awaitable runner for a sync callable, used on the caller's event loop."""


class Dispatcher(Protocol):
    async def call(self, fn: Callable[[], T]) -> T:
        """Run ``fn`` on the appropriate thread; awaited on the Copilot loop."""
        ...


class CallerThreadExecutor:
    """Work queue serviced by a blocked caller thread until a future completes."""

    _WAKE = object()

    def __init__(self) -> None:
        self._queue: queue.SimpleQueue[Any] = queue.SimpleQueue()
        self._lock = threading.Lock()
        self._closed = False
        self.thread_id = threading.get_ident()

    def submit(self, fn: Callable[[], T]) -> concurrent.futures.Future[T]:
        future: concurrent.futures.Future[T] = concurrent.futures.Future()
        with self._lock:
            if self._closed:
                raise RuntimeError("The calling thread is no longer waiting for this invocation")
            self._queue.put((fn, future))
        return future

    def run_until(self, future: concurrent.futures.Future[T], timeout: float | None) -> T:
        """Service submitted work on this thread until ``future`` is done.

        Raises :class:`TimeoutError` (leaving ``future`` running) when ``timeout`` elapses.
        """
        if threading.get_ident() != self.thread_id:
            raise RuntimeError("run_until must be called on the thread that created the executor")
        future.add_done_callback(lambda _: self._queue.put(self._WAKE))
        deadline = None if timeout is None else time.monotonic() + timeout
        try:
            while not future.done():
                remaining = None if deadline is None else deadline - time.monotonic()
                if remaining is not None and remaining <= 0:
                    raise TimeoutError
                try:
                    item = self._queue.get(timeout=remaining)
                except queue.Empty:
                    raise TimeoutError from None
                if item is not self._WAKE:
                    self._run(*item)
        finally:
            self._close()
        return future.result()

    @staticmethod
    def _run(fn: Callable[[], Any], future: concurrent.futures.Future[Any]) -> None:
        if not future.set_running_or_notify_cancel():
            return
        try:
            future.set_result(fn())
        except BaseException as exc:  # noqa: BLE001 - propagated through the future
            future.set_exception(exc)

    def _close(self) -> None:
        with self._lock:
            self._closed = True
        while True:
            try:
                item = self._queue.get_nowait()
            except queue.Empty:
                return
            if item is not self._WAKE:
                _, future = item
                if future.set_running_or_notify_cancel():
                    future.set_exception(
                        RuntimeError("The calling thread stopped waiting for this invocation")
                    )


class ThreadPoolDispatcher:
    """Runs callbacks in a bounded thread pool; used when no caller is waiting."""

    def __init__(
        self,
        max_workers: int = 4,
        wrapper: Callable[[Callable[[], Any]], Any] | None = None,
    ) -> None:
        self._pool = concurrent.futures.ThreadPoolExecutor(
            max_workers=max_workers, thread_name_prefix="copilot-callback"
        )
        self._wrapper = wrapper

    async def call(self, fn: Callable[[], T]) -> T:
        target = fn if self._wrapper is None else (lambda: self._wrapper(fn))
        return await asyncio.get_running_loop().run_in_executor(self._pool, target)


_fallback = ThreadPoolDispatcher()


def default_fallback() -> ThreadPoolDispatcher:
    return _fallback


class CallerThreadDispatcher:
    """Runs callbacks on a sync caller's thread through a :class:`CallerThreadExecutor`."""

    def __init__(self, executor: CallerThreadExecutor, fallback: Dispatcher | None = None) -> None:
        self.executor = executor
        self.fallback = fallback or _fallback
        # Caller's context, used for async callbacks that run on the Copilot loop.
        self.context = contextvars.copy_context()

    async def call(self, fn: Callable[[], T]) -> T:
        try:
            future = self.executor.submit(fn)
        except RuntimeError:
            return await self.fallback.call(fn)
        return await asyncio.wrap_future(future)


async def _run_in_default_executor(fn: Callable[[], T]) -> T:
    return await asyncio.get_running_loop().run_in_executor(None, fn)


class AsyncCallerDispatcher:
    """Runs callbacks for an async caller on the caller's loop, in the caller's context."""

    def __init__(
        self,
        loop: asyncio.AbstractEventLoop,
        context: contextvars.Context,
        sync_runner: SyncRunner | None = None,
    ) -> None:
        self.loop = loop
        self.context = context
        self.sync_runner = sync_runner or _run_in_default_executor

    async def call(self, fn: Callable[[], T]) -> T:
        context = self.context.copy()

        async def run() -> T:
            return await self.sync_runner(lambda: context.run(fn))

        return await asyncio.wrap_future(asyncio.run_coroutine_threadsafe(run(), self.loop))
