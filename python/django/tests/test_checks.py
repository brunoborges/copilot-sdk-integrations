import pytest
from django.core.management import call_command
from django.test import override_settings

from copilot_django import CopilotAgent, checks, policies


def ids(messages):
    return sorted(m.id for m in messages)


def test_project_is_clean(monkeypatch):
    monkeypatch.setenv("COPILOT_CLI_PATH", "/bin/copilot")
    assert checks.check_settings() + checks.check_agents() + checks.check_runtime() == []


def test_unknown_settings_keys():
    with override_settings(COPILOT={"RUNTIM": {}, "RUNTIME": {"CONNECTION": "uri"}, "AGENTS": {"support": {"MODLE": "x"}}}):
        messages = checks.check_settings()
    assert ids(messages) == ["copilot.E001"] * 3
    assert any("URL" in m.msg for m in messages)


def test_agent_problems_map_to_ids(isolated_registry):
    class RefsAgent(CopilotAgent):
        tools = ["missing_tool"]

    class FiltersAgent(CopilotAgent):
        available_tools = ["builtin:view"]
        excluded_tools = ["builtin:bash"]

    class ByokAgent(CopilotAgent):
        provider = {"type": "openai", "base_url": "http://localhost"}

    class OpenAgent(CopilotAgent):
        permissions = policies.allow_all()

    class DupeAgent(CopilotAgent):
        name = "support"

    assert ids(checks.check_agents()) == [
        "copilot.E002",
        "copilot.E003",
        "copilot.E004",
        "copilot.E005",
        "copilot.W002",
    ]


def test_stdio_with_many_workers(monkeypatch):
    monkeypatch.setenv("COPILOT_CLI_PATH", "/bin/copilot")
    monkeypatch.setenv("WEB_CONCURRENCY", "8")
    assert ids(checks.check_runtime()) == ["copilot.W003"]


def test_missing_runtime_binary(monkeypatch):
    monkeypatch.delenv("COPILOT_CLI_PATH", raising=False)
    monkeypatch.setattr("copilot._cli_download.get_cached_cli_path", lambda *a: None)
    assert ids(checks.check_runtime()) == ["copilot.W001"]


def test_manage_py_check_passes(monkeypatch):
    monkeypatch.setenv("COPILOT_CLI_PATH", "/bin/copilot")
    call_command("check")
