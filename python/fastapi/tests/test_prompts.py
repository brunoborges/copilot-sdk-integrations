import pytest

from copilot_fastapi import CopilotAgent, PromptRenderError, prompt
from copilot_fastapi.agents import render_prompt

from tests.shop import USERS, SupportAgent, Triage


def test_render_is_strict():
    assert render_prompt("Hi {name}", {"name": "{evil}"}) == "Hi {evil}"
    with pytest.raises(PromptRenderError):
        render_prompt("Hi {missing}", {})
    with pytest.raises(PromptRenderError):
        render_prompt("Hi {user.nope}", {"user": object()})


def test_undefined_variables_fail_at_definition():
    with pytest.raises(PromptRenderError, match="nope"):

        class Bad(CopilotAgent):
            @prompt("{nope}")
            def ask_bad(self, x: int) -> str: ...


def test_sync_prompt(copilot, fake):
    fake.agent("support").replies("Shipped")
    assert copilot.get(SupportAgent, user=USERS["ana"]).where_is(1) == "Shipped"
    assert fake.prompts("support") == ["Where is order 1? Asked by Ana."]


async def test_async_and_typed_prompts(copilot, fake):
    fake.agent("support").replies("Soon").replies_json({"priority": "high", "summary": "late"})
    agent = copilot.get(SupportAgent)
    assert await agent.awhere_is(2) == "Soon"
    result = await agent.triage("my order is late")
    assert result == Triage(priority="high", summary="late")


async def test_conversation_prompt(copilot, fake):
    fake.agent("support").replies("one", "two")
    agent = copilot.get(SupportAgent, user=USERS["bob"])
    await agent.chat("a", "c-9")
    await agent.chat(text="b", conversation_id="c-9")
    assert len(fake.created) == 1 and len(fake.resumed) == 1
    assert fake.prompts("support") == ["Continue: a", "Continue: b"]


def test_only_the_return_annotation_is_resolved(copilot, fake):
    class LateAgent(CopilotAgent):
        @prompt("Go {x}")
        def go(self, x: "NotImportedAtRuntime") -> "LateModel": ...  # noqa: F821

    from pydantic import BaseModel

    global LateModel

    class LateModel(BaseModel):
        ok: bool

    fake.agent("late").replies_json({"ok": True})
    try:
        assert copilot.get(LateAgent).go(1) == LateModel(ok=True)
    finally:
        del globals()["LateModel"]


def test_unresolvable_return_annotation_is_a_clear_error(copilot):
    from copilot_fastapi import CopilotConfigurationError

    class LostAgent(CopilotAgent):
        @prompt("Go")
        def go(self) -> "Missing": ...  # noqa: F821

    with pytest.raises(CopilotConfigurationError, match="result=Model"):
        copilot.get(LostAgent).go()
