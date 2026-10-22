"""Pytest plugin (auto-loaded via the ``pytest11`` entry point)."""

from __future__ import annotations

from collections.abc import Iterator

import pytest

from copilot_agents.testing import CopilotTestRuntime


@pytest.fixture
def copilot_runtime() -> Iterator[CopilotTestRuntime]:
    """A scripted Copilot runtime installed for the test; no real runtime is started."""
    from copilot_django.test import copilot_test_runtime

    with copilot_test_runtime() as fake:
        yield fake
