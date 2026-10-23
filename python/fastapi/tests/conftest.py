from __future__ import annotations

import pytest

from copilot_fastapi import Copilot, CopilotSettings
from copilot_fastapi.testing import copilot_test_runtime

from tests.shop import LockedAgent, SupportAgent, current_user


@pytest.fixture(autouse=True)
def _env(monkeypatch, tmp_path):
    for name in list(__import__("os").environ):
        if name.startswith("COPILOT_"):
            monkeypatch.delenv(name)
    monkeypatch.chdir(tmp_path)


@pytest.fixture
def settings(tmp_path) -> CopilotSettings:
    return CopilotSettings(runtime={"base_directory": tmp_path / "copilot"})


@pytest.fixture
def copilot(settings) -> Copilot:
    return Copilot(settings, agents=[SupportAgent, LockedAgent], current_user=current_user)


@pytest.fixture
def fake(copilot):
    with copilot_test_runtime(copilot) as runtime:
        yield runtime
