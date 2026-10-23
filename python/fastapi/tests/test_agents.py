from pathlib import Path

import pytest

from copilot_fastapi import (
    Copilot,
    CopilotAgent,
    CopilotConfigurationError,
    CopilotConversationNotFound,
    CopilotSettings,
    UserTokenResolver,
    policies,
)
from copilot_fastapi.agents import default_name
from copilot_fastapi.testing import copilot_test_runtime
from copilot_agents import derive_session_id

from tests.shop import USERS, LockedAgent, SupportAgent


def test_default_names():
    assert default_name("SupportAgent") == "support"
    assert default_name("OrderTriageAgent") == "order_triage"
    assert default_name("HTTPHelperAgent") == "http_helper"
    assert SupportAgent.name == "support"


def test_definition_resolves_tools_and_defaults(copilot):
    definition = copilot.definition(SupportAgent)
    assert definition.type_name == "tests.shop.SupportAgent"
    assert [t.name for t in definition.tools] == ["lookup_order", "refund_order", "broken"]
    assert definition.instructions == "You help customers with shop orders."
    assert definition.timeout == 300
    assert copilot.definition(SupportAgent) is definition


def test_settings_override_class_attributes(tmp_path):
    settings = CopilotSettings(
        defaults={"model": "default-model"},
        agents={"support": {"model": "gpt-5", "timeout": 30}},
        mcp_servers={"docs": {"type": "http", "url": "https://example.com/mcp"}},
    )

    class DocsAgent(CopilotAgent):
        mcp_servers = ["docs"]
        permissions = {"allow_mcp_servers": ["docs"]}

    copilot = Copilot(settings, agents=[SupportAgent, DocsAgent])
    assert copilot.definition(SupportAgent).model == "gpt-5"
    assert copilot.definition(SupportAgent).timeout == 30
    docs = copilot.definition(DocsAgent)
    assert docs.model == "default-model"
    assert docs.mcp_servers["docs"]["url"] == "https://example.com/mcp"


def test_message_source_from_class_attribute_and_settings():
    from copilot import AgentMessageSource

    class NightlyAgent(CopilotAgent):
        message_source = "system"

    copilot = Copilot(CopilotSettings(agents={"support": {"message_source": "agent-ops"}}), agents=[SupportAgent, NightlyAgent])
    assert copilot.definition(NightlyAgent).message_source == "system"
    assert copilot.definition(SupportAgent).message_source == AgentMessageSource("ops")
    bad = Copilot(CopilotSettings(agents={"support": {"message_source": "root"}}), agents=[SupportAgent])
    with pytest.raises(CopilotConfigurationError, match="message_source"):
        bad.definition(SupportAgent)


def test_default_policy_denies(copilot, fake):
    fake.agent("locked").calls_tool("lookup_order", {"order_id": 1}).replies("no")
    assert copilot.get(LockedAgent).ask("x") == "no"
    assert not fake.tool_calls("locked")[0].approved


def test_unknown_references_are_reported(copilot):
    class BrokenAgent(CopilotAgent):
        tools = ["no.such.tool"]
        mcp_servers = ["nowhere"]
        provider = "missing"

    copilot.register(BrokenAgent)
    with pytest.raises(CopilotConfigurationError) as info:
        copilot.check()
    assert {issue.kind for issue in info.value.issues} == {"reference"}
    assert len(info.value.issues) == 3


def test_abstract_agents_cannot_be_registered_or_built(copilot):
    class BaseHelper(CopilotAgent):
        abstract = True
        permissions = policies.allow_registered_tools()

    class ConcreteHelperAgent(BaseHelper):
        pass

    assert not ConcreteHelperAgent.abstract
    with pytest.raises(CopilotConfigurationError):
        copilot.register(BaseHelper)
    with pytest.raises(CopilotConfigurationError):
        BaseHelper(copilot)
    assert copilot.definition(ConcreteHelperAgent).name == "concrete_helper"


def test_duplicate_names_are_rejected(copilot):
    class Other(CopilotAgent):
        name = "support"

    with pytest.raises(CopilotConfigurationError) as info:
        copilot.register(Other)
    assert info.value.issues[0].kind == "duplicate"


def test_allow_all_is_a_warning(settings):
    class LooseAgent(CopilotAgent):
        permissions = "allow_all"

    warnings = Copilot(settings, agents=[LooseAgent]).check()
    assert any("allow_all" in w for w in warnings)


def test_conversations_resume_and_are_partitioned_per_user(copilot, fake):
    ana, bob = USERS["ana"], USERS["bob"]
    fake.agent("support").replies("1", "2", "3")
    ana_conv = copilot.get(SupportAgent, user=ana).conversation("ticket-1")
    ana_conv.send("first")
    ana_conv.send("second")
    copilot.get(SupportAgent, user=bob).conversation("ticket-1").send("hi")
    assert len(fake.created) == 2
    assert fake.resumed == [ana_conv.session_id]
    assert ana_conv.session_id == derive_session_id("tests.shop.SupportAgent", "support", "ticket-1", "1")
    with pytest.raises(CopilotConversationNotFound):
        copilot.get(SupportAgent, user=bob).resume("ticket-404")
    assert copilot.get(SupportAgent, user=ana).resume("ticket-1").exists()
    ana_conv.delete()
    assert not ana_conv.exists()


def test_users_without_a_key_are_rejected(copilot, fake):
    fake.agent("support").replies("x")
    with pytest.raises(CopilotConfigurationError, match="user_key"):
        copilot.get(SupportAgent, user=object()).ask("hi")


def test_isolated_workspace_is_released(copilot, fake, settings):
    fake.agent("support").replies("ok")
    copilot.get(SupportAgent).ask("hi")
    options = fake.last_session_options("support")
    workspace = Path(options["working_directory"])
    assert workspace.parent == settings.base_directory / "workspaces"
    assert not workspace.exists()


async def test_async_api(copilot, fake):
    fake.agent("support").replies("a", "b")
    agent = copilot.get(SupportAgent, user=USERS["ana"])
    assert await agent.aask("one") == "a"
    conversation = agent.conversation("c1")
    assert await conversation.asend("two") == "b"
    assert await conversation.aexists()
    await (await agent.aresume("c1")).adelete()
    assert not await conversation.aexists()


class _NoTokens(UserTokenResolver):
    def token_for_user(self, user):
        return None


def test_user_token_resolver_uses_the_apps_user_key(settings, fake):
    copilot = Copilot(settings, agents=[SupportAgent], user_key=lambda u: u["email"], token_resolver=_NoTokens())
    a = copilot.get(SupportAgent, user={"email": "a@x"}).conversation("c").session_id
    b = copilot.get(SupportAgent, user={"email": "b@x"}).conversation("c").session_id
    assert a != b


def test_user_token_resolver_rejects_users_without_a_key(settings, fake):
    copilot = Copilot(settings, agents=[SupportAgent], token_resolver=_NoTokens())
    with pytest.raises(CopilotConfigurationError, match="partition"):
        copilot.get(SupportAgent, user={"email": "a@x"}).conversation("c").session_id


class SearchingAgent(CopilotAgent):
    tool_search = {"enabled": True, "defer_threshold": 5}
    permissions = "deny"


def test_tool_search_is_not_passed_by_default(copilot, fake):
    fake.agent("support").replies("ok")
    copilot.get(SupportAgent).ask("hi")
    assert copilot.definition(SupportAgent).tool_search is None
    assert "tool_search" not in fake.last_session_options("support")


def test_tool_search_attribute_passed_to_create_and_resume(settings):
    copilot = Copilot(settings, agents=[SearchingAgent])
    with copilot_test_runtime(copilot) as fake:
        fake.agent("searching").replies("1", "2")
        conversation = copilot.get(SearchingAgent, user=USERS["ana"]).conversation("c1")
        conversation.send("first")
        created = fake.last_session_options("searching")["tool_search"]
        conversation.send("second")
        assert fake.resumed == [conversation.session_id]
        resumed = fake.last_session_options("searching")["tool_search"]
    assert created == resumed == {"enabled": True, "defer_threshold": 5}
    assert created is not resumed


def test_tool_search_settings_override(tmp_path):
    settings = CopilotSettings(agents={"searching": {"tool_search": {"enabled": False}}})
    assert Copilot(settings, agents=[SearchingAgent]).definition(SearchingAgent).tool_search == {"enabled": False}
    settings = CopilotSettings(agents={"searching": {"tool_search": {"defer_threshold": 50}}})
    assert Copilot(settings, agents=[SearchingAgent]).definition(SearchingAgent).tool_search == {"defer_threshold": 50}


def test_tool_search_validation(settings):
    class BadSearchAgent(CopilotAgent):
        tool_search = {"enabled": True, "defer_threshold": 0}

    with pytest.raises(CopilotConfigurationError, match="defer_threshold must be an int >= 1"):
        Copilot(settings, agents=[BadSearchAgent]).definition(BadSearchAgent)
    overridden = CopilotSettings(agents={"searching": {"tool_search": {"enabled": False, "defer_threshold": 5}}})
    with pytest.raises(CopilotConfigurationError, match="no effect when enabled is False"):
        Copilot(overridden, agents=[SearchingAgent]).definition(SearchingAgent)
