from __future__ import annotations

import pytest

from copilot_agents import CopilotRuntime
from copilot_agents.testing import CopilotTestRuntime


@pytest.fixture
def fake() -> CopilotTestRuntime:
    return CopilotTestRuntime()


@pytest.fixture
def runtime(fake: CopilotTestRuntime):
    rt = CopilotRuntime(fake.client_factory, name="test")
    yield rt
    rt.shutdown()
