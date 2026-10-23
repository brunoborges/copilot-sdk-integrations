import pytest

from copilot_agents import AgentDefinition, CopilotConfigurationError, tool


@tool
def lookup(order_id: int) -> str:
    """Look up an order."""
    return str(order_id)


def kinds(**kwargs):
    with pytest.raises(CopilotConfigurationError) as info:
        AgentDefinition(name="a", **kwargs)
    return {issue.kind for issue in info.value.issues}


def test_allow_list_must_include_custom_tools():
    assert kinds(tools=[lookup], available_tools=["builtin:view"]) == {"tool-filters"}
    AgentDefinition(name="a", tools=[lookup], available_tools=["builtin:view", "custom:*"])


def test_allow_and_deny_lists_are_exclusive():
    assert kinds(available_tools=["custom:*"], excluded_tools=["builtin:bash"]) == {"tool-filters"}


def test_built_in_tools_false_excludes_builtins():
    definition = AgentDefinition(name="a", built_in_tools=False)
    assert "builtin:*" in definition.effective_excluded_tools
    assert kinds(built_in_tools=False, available_tools=["custom:*"]) == {"tool-filters"}


def test_byok_requires_model_and_duplicates_detected():
    assert kinds(provider={"type": "openai", "base_url": "http://x"}) == {"byok"}
    assert kinds(tools=[lookup, lookup]) == {"duplicate"}
    assert kinds(timeout=0) == {"invalid"}


def test_tool_search_validation():
    assert AgentDefinition(name="a").tool_search is None
    config = {"enabled": True, "defer_threshold": 10}
    definition = AgentDefinition(name="a", tool_search=config)
    assert definition.tool_search == config and definition.tool_search is not config
    AgentDefinition(name="a", tool_search={"enabled": False})
    AgentDefinition(name="a", tool_search={"defer_threshold": 1})
    for bad in (
        "on",
        {"enabled": "yes"},
        {"defer_threshold": 0},
        {"defer_threshold": True},
        {"defer_threshold": "30"},
        {"enabled": False, "defer_threshold": 10},
        {"threshold": 10},
    ):
        assert kinds(tool_search=bad) == {"invalid"}, bad


def test_tool_schema_from_signature():
    assert lookup.name == "lookup"
    assert lookup.parameters["properties"]["order_id"]["type"] == "integer"
    assert lookup.parameters["required"] == ["order_id"]
