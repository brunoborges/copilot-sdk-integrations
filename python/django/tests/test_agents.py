import pytest
from django.test import override_settings

from copilot_django import CopilotAgent, CopilotConfigurationError, CopilotConversationNotFound, policies
from copilot_django.agents import default_name
from copilot_django.registry import agents
from shop.copilot_agents import LockedAgent, SupportAgent


def test_default_names():
    assert default_name("SupportAgent") == "support"
    assert default_name("OrderTriageAgent") == "order_triage"
    assert default_name("HTTPHelperAgent") == "http_helper"
    assert agents["support"] is SupportAgent
    assert agents["locked"] is LockedAgent


def test_definition_resolves_tools_servers_and_identity():
    definition = SupportAgent.definition()
    assert definition.type_name == "shop.copilot_agents.SupportAgent"
    assert [t.name for t in definition.tools] == ["lookup_order", "refund_order", "count_orders"]
    assert definition.mcp_servers["docs"]["url"] == "https://example.com/mcp"
    assert definition.instructions == "You help customers with shop orders."
    assert definition.timeout == 300


def test_settings_override_class_attributes():
    with override_settings(COPILOT={"AGENTS": {"support": {"MODEL": "gpt-5", "TIMEOUT": 30}}, "MCP_SERVERS": {"docs": {"type": "http", "url": "https://x"}}}):
        definition = SupportAgent.definition()
        assert definition.model == "gpt-5"
        assert definition.timeout == 30
    assert SupportAgent.definition().model is None


def test_default_policy_denies(copilot_runtime):
    copilot_runtime.agent("locked").calls_tool("lookup_order", {"order_id": 1}).replies("no")
    assert LockedAgent(user=None).ask("x") == "no"
    assert not copilot_runtime.tool_calls("locked")[0].approved


def test_unknown_references_are_reported(isolated_registry):
    class BrokenAgent(CopilotAgent):
        tools = ["no_such_tool"]
        mcp_servers = ["nowhere"]
        provider = "missing"

    with pytest.raises(CopilotConfigurationError) as info:
        BrokenAgent.definition()
    assert {issue.kind for issue in info.value.issues} == {"reference"}
    assert len(info.value.issues) == 3


def test_abstract_agents_are_not_registered(isolated_registry):
    class BaseHelper(CopilotAgent):
        abstract = True
        permissions = policies.allow_registered_tools()

    class ConcreteHelperAgent(BaseHelper):
        pass

    assert "base_helper" not in isolated_registry.agents
    assert isolated_registry.agents["concrete_helper"] is ConcreteHelperAgent


def test_duplicate_names_are_recorded(isolated_registry):
    class One(CopilotAgent):
        name = "dupe"

    class Two(CopilotAgent):
        name = "dupe"

    assert isolated_registry.agents["dupe"] is One
    assert any("dupe" in d for d in isolated_registry.duplicates)


@pytest.mark.django_db
def test_conversations_resume_and_are_partitioned_per_user(copilot_runtime, django_user_model):
    ana = django_user_model.objects.create_user("ana")
    bob = django_user_model.objects.create_user("bob")
    copilot_runtime.agent("support").replies("1", "2", "3")
    ana_conv = SupportAgent(user=ana).conversation("ticket-1")
    ana_conv.send("first")
    ana_conv.send("second")
    SupportAgent(user=bob).conversation("ticket-1").send("hi")
    assert len(copilot_runtime.created) == 2
    assert copilot_runtime.resumed == [ana_conv.session_id]
    with pytest.raises(CopilotConversationNotFound):
        SupportAgent(user=bob).resume("ticket-404")
    assert SupportAgent(user=ana).resume("ticket-1").exists()
    ana_conv.delete()
    assert not ana_conv.exists()


def test_isolated_workspace_is_created_and_removed(copilot_runtime, settings):
    copilot_runtime.agent("locked").replies("ok")
    LockedAgent(user=None).ask("x")
    options = copilot_runtime.last_session_options("locked")
    workspace = options["working_directory"]
    assert workspace.endswith(copilot_runtime.created[0])
    import os

    assert not os.path.exists(workspace)
