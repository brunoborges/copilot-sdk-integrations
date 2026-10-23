"""Test helpers: swap a ``Copilot`` runtime for a scripted in-memory fake.

::

    @pytest.fixture
    def fake_copilot():
        with copilot_test_runtime(copilot) as fake:
            yield fake

    def test_ask(client, fake_copilot):
        fake_copilot.agent("support").replies("Shipped")
        assert client.post("/support", json={"text": "Where is 42?"}).json() == {"answer": "Shipped"}
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

from copilot_agents.testing import AgentScript, CopilotTestRuntime, RecordedToolCall

from .copilot import Copilot

__all__ = ["AgentScript", "CopilotTestRuntime", "RecordedToolCall", "copilot_test_runtime"]


@contextmanager
def copilot_test_runtime(copilot: Copilot, **kwargs: Any) -> Iterator[CopilotTestRuntime]:
    """Install a :class:`CopilotTestRuntime` on ``copilot`` for the duration of the block."""
    with copilot.test_runtime(**kwargs) as fake:
        yield fake
