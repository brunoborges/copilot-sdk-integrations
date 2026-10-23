"""Tracks which runtime sessions have a turn in flight, so deletion never races a turn.

Every method must be called on the Copilot loop thread (turns, deletes, and sweeps all run
there), so plain dicts are enough and no lock is needed. :meth:`busy` is a single dict
lookup and is safe to call from any thread.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Awaitable
from contextlib import asynccontextmanager
from typing import TypeVar

T = TypeVar("T")


class SessionActivity:
    """Turns share a session; deletion claims it exclusively after in-flight turns end."""

    def __init__(self) -> None:
        self._turns: dict[str, int] = {}
        self._claimed: set[str] = set()
        self._waiters: dict[str, list[asyncio.Future[None]]] = {}

    def busy(self, session_id: str) -> bool:
        """Whether a turn is in flight on the session, or it is claimed for deletion."""
        return session_id in self._turns or session_id in self._claimed

    def try_claim(self, session_id: str) -> bool:
        """Claim the session exclusively unless it is busy; pair with :meth:`release`."""
        if self.busy(session_id):
            return False
        self._claimed.add(session_id)
        return True

    def release(self, session_id: str) -> None:
        self._claimed.discard(session_id)
        self._notify(session_id)

    @asynccontextmanager
    async def exclusive(self, session_id: str) -> AsyncIterator[None]:
        """Wait for in-flight turns to finish, then hold the session exclusively."""
        while not self.try_claim(session_id):
            await self._wait(session_id)
        try:
            yield
        finally:
            self.release(session_id)

    async def track(self, session_id: str, turn: Awaitable[T]) -> T:
        """Await ``turn`` while the session is marked as in flight."""
        try:
            while session_id in self._claimed:
                await self._wait(session_id)
        except BaseException:
            close = getattr(turn, "close", None)
            if close is not None:
                close()
            raise
        self._turns[session_id] = self._turns.get(session_id, 0) + 1
        try:
            return await turn
        finally:
            remaining = self._turns[session_id] - 1
            if remaining:
                self._turns[session_id] = remaining
            else:
                del self._turns[session_id]
                self._notify(session_id)

    async def _wait(self, session_id: str) -> None:
        future: asyncio.Future[None] = asyncio.get_running_loop().create_future()
        self._waiters.setdefault(session_id, []).append(future)
        try:
            await future
        finally:
            waiters = self._waiters.get(session_id)
            if waiters is not None and future in waiters:
                waiters.remove(future)
                if not waiters:
                    del self._waiters[session_id]

    def _notify(self, session_id: str) -> None:
        for future in self._waiters.pop(session_id, ()):
            if not future.done():
                future.set_result(None)
