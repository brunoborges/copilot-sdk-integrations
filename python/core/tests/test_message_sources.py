from __future__ import annotations

import asyncio

import pytest
from copilot import AgentMessageSource

from copilot_agents import (
    AgentDefinition,
    AgentInvoker,
    CopilotConfigurationError,
    current_message_source,
    message_source,
    message_sources,
    tool,
)


def test_parse_and_validate_agent_ids():
    assert message_sources.parse("user") == "user"
    assert message_sources.parse("system") == "system"
    assert message_sources.parse("agent-incident.bot_1") == AgentMessageSource("incident.bot_1")
    assert message_sources.parse("") is None
    assert message_sources.parse(None) is None
    for bad in ("User", " user", "agent-", "agent-a b", "agent-a\n", "agent--x", "agent-" + "a" * 129, "admin"):
        with pytest.raises(ValueError):
            message_sources.parse(bad)
    with pytest.raises(ValueError):
        message_sources.agent("bad/id")


def test_definition_validates_and_normalizes():
    assert AgentDefinition(name="a", message_source="agent-ops").message_source == AgentMessageSource("ops")
    with pytest.raises(CopilotConfigurationError, match="message_source"):
        AgentDefinition(name="a", message_source="root")


def _invoker(runtime, name, **kwargs):
    kwargs.setdefault("permissions", "allow_registered_tools")
    return AgentInvoker(AgentDefinition(name=name, **kwargs), runtime)


def test_default_config_and_scope(fake, runtime):
    fake.agent("support").replies("a", "b", "c")
    fake.agent("nightly").replies("d")
    _invoker(runtime, "support").invoke("plain")
    _invoker(runtime, "nightly", message_source="system").invoke("cron")
    with message_source("agent-reporting"):
        assert current_message_source() == AgentMessageSource("reporting")
        _invoker(runtime, "support").invoke("scoped")
    assert current_message_source() is None
    with message_source(message_sources.USER):
        _invoker(runtime, "support").invoke("user")
    assert fake.sources("support") == [None, "agent-reporting", "user"]
    assert fake.sources("nightly") == ["system"]


def test_delegation_is_labeled_and_outer_scope_does_not_leak(fake, runtime):
    helper = _invoker(runtime, "helper", message_source="system")

    @tool
    def ask_helper(question: str) -> str:
        """Ask the helper agent."""
        return helper.invoke(question)

    @tool
    def ask_helper_as_user(question: str) -> str:
        """Ask the helper agent on the user's behalf."""
        with message_source("user"):
            return helper.invoke(question)

    delegator = _invoker(runtime, "delegator", tools=[ask_helper, ask_helper_as_user])
    fake.agent("delegator").calls_tool("ask_helper", {"question": "q1"}).replies("done")
    fake.agent("delegator").calls_tool("ask_helper", {"question": "q2"}).replies("done")
    fake.agent("delegator").calls_tool("ask_helper_as_user", {"question": "q3"}).replies("done")
    fake.agent("helper").replies("h1", "h2", "h3")

    delegator.invoke("first")
    with message_source("user"):
        delegator.invoke("second")
    delegator.invoke("third")

    assert fake.sources("delegator") == [None, "user", None]
    # The outer "user" scope labels only the outer prompt; a scope opened in the tool applies.
    assert fake.sources("helper") == ["agent-delegator", "agent-delegator", "user"]


def test_async_delegation_and_scope(fake, runtime):
    helper = _invoker(runtime, "helper")

    @tool
    def ask_helper(question: str) -> str:
        """Ask the helper agent (a sync tool, run off the caller's loop for ainvoke)."""
        return helper.invoke(question)

    delegator = _invoker(runtime, "delegator", tools=[ask_helper])
    fake.agent("delegator").calls_tool("ask_helper", {"question": "q"}).replies("done")
    fake.agent("helper").replies("h")

    async def main():
        with message_source("system"):
            await delegator.ainvoke("go")

    asyncio.run(main())
    assert fake.sources("delegator") == ["system"]
    assert fake.sources("helper") == ["agent-delegator"]
