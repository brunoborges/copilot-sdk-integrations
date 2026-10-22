import subprocess
import sys

import pytest
from django.core.management import call_command
from django.core.management.base import CommandError


def test_copilot_check_reports_runtime_and_agents(copilot_runtime, capsys):
    call_command("copilot_check")
    out = capsys.readouterr().out
    assert "Auth: authenticated as copilot-test" in out
    assert "Models: test-model" in out
    assert "Agent 'support'" in out
    assert "tools: lookup_order, refund_order, count_orders" in out
    assert "s3cret" not in out and "***" in out


def test_copilot_check_without_runtime(capsys):
    call_command("copilot_check", "--no-runtime")
    assert "Agent 'locked'" in capsys.readouterr().out


def test_download_runtime_wraps_sdk_cli(monkeypatch):
    seen = {}

    def run(command, check):
        seen["command"] = command
        return subprocess.CompletedProcess(command, 0)

    monkeypatch.setattr(subprocess, "run", run)
    call_command("copilot_download_runtime", "--force", "--runtime-version", "1.2.3")
    assert seen["command"] == [sys.executable, "-m", "copilot", "download-runtime", "--force", "--version", "1.2.3"]

    monkeypatch.setattr(subprocess, "run", lambda command, check: subprocess.CompletedProcess(command, 2))
    with pytest.raises(CommandError):
        call_command("copilot_download_runtime")
