import pytest
from pydantic import ValidationError

from copilot_fastapi import CopilotSettings


def test_defaults(tmp_path):
    settings = CopilotSettings()
    assert settings.runtime.connection == "stdio"
    assert settings.defaults.permissions == "deny"
    assert settings.base_directory == tmp_path / ".copilot"


def test_environment(monkeypatch):
    monkeypatch.setenv("COPILOT_RUNTIME__CONNECTION", "uri")
    monkeypatch.setenv("COPILOT_RUNTIME__URL", "http://copilot:8080")
    monkeypatch.setenv("COPILOT_RUNTIME__CONNECTION_TOKEN", "s3cret")
    monkeypatch.setenv("COPILOT_DEFAULTS__MODEL", "gpt-5")
    monkeypatch.setenv("COPILOT_AGENTS", '{"support": {"model": "claude", "timeout": 30}}')
    settings = CopilotSettings()
    assert settings.runtime.url == "http://copilot:8080"
    assert "s3cret" not in repr(settings)
    assert settings.defaults.model == "gpt-5"
    assert settings.agent_overrides("support") == {"model": "claude", "timeout": 30}
    assert settings.agent_overrides("other") == {}


def test_uri_requires_url():
    with pytest.raises(ValidationError, match="url"):
        CopilotSettings(runtime={"connection": "uri"})


def test_unknown_override_keys_are_rejected():
    with pytest.raises(ValidationError):
        CopilotSettings(agents={"support": {"modle": "gpt-5"}})


def test_tool_search_override(monkeypatch):
    monkeypatch.setenv("COPILOT_AGENTS", '{"support": {"tool_search": {"defer_threshold": "10"}}}')
    assert CopilotSettings().agent_overrides("support") == {"tool_search": {"defer_threshold": 10}}
    settings = CopilotSettings(agents={"support": {"tool_search": {"enabled": False, "defer_threshold": None}}})
    assert settings.agent_overrides("support") == {"tool_search": {"enabled": False}}
    for bad in ({"threshold": 5}, {"defer_threshold": 0}, {"defer_threshold": True}, {"enabled": "maybe"}):
        with pytest.raises(ValidationError):
            CopilotSettings(agents={"support": {"tool_search": bad}})
