"""Test helpers: swap the runtime for a scripted in-memory fake."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

from copilot_agents.testing import CopilotTestRuntime

from .runtime import get_runtime

__all__ = ["CopilotTestMixin", "CopilotTestRuntime", "copilot_test_runtime"]


@contextmanager
def copilot_test_runtime(**kwargs: Any) -> Iterator[CopilotTestRuntime]:
    """Install a :class:`CopilotTestRuntime` on the Django Copilot runtime."""
    fake = CopilotTestRuntime(**kwargs)
    with fake.install(get_runtime()):
        yield fake


class CopilotTestMixin:
    """For ``django.test`` test cases: provides ``self.copilot`` (a fresh fake per test)."""

    copilot: CopilotTestRuntime

    def setUp(self) -> None:
        super().setUp()  # type: ignore[misc]
        manager = copilot_test_runtime()
        self.copilot = manager.__enter__()
        self.addCleanup(manager.__exit__, None, None, None)  # type: ignore[attr-defined]
