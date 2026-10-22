import pytest

from copilot_django import CopilotReply
from copilot_django.templating import PromptRenderError
from shop.copilot_agents import Decision, SupportAgent


def test_prompt_renders_and_parses_typed_result(copilot_runtime):
    copilot_runtime.agent("support").replies_json({"approve": True, "reason": "late"})
    decision = SupportAgent(user=None).decide_refund(42, message="It was late")
    assert decision == Decision(approve=True, reason="late")
    assert copilot_runtime.prompts("support") == ["Should we refund order 42? Customer says: It was late"]


def test_prompt_autoescape_is_off(copilot_runtime):
    copilot_runtime.agent("support").replies_json({"approve": False, "reason": "-"})
    SupportAgent(user=None).decide_refund(1, message="<b>\"quoted\" & more</b>")
    assert copilot_runtime.prompts("support")[0].endswith("<b>\"quoted\" & more</b>")


def test_undefined_variables_raise(copilot_runtime):
    with pytest.raises(PromptRenderError, match="missing"):
        SupportAgent(user=None).broken()
    assert copilot_runtime.prompts("support") == []


def test_template_file_and_reply_metadata(copilot_runtime):
    copilot_runtime.agent("support").replies("hi")
    reply = SupportAgent(user=None).greet()
    assert isinstance(reply, CopilotReply)
    assert copilot_runtime.prompts("support") == ["Greet the guest."]


def test_conversation_id_makes_prompt_stateful(copilot_runtime):
    copilot_runtime.agent("support").replies("a", "b")
    agent = SupportAgent(user=None)
    agent.greet(conversation_id="c1")
    agent.greet(conversation_id="c1")
    assert len(copilot_runtime.created) == 1 and len(copilot_runtime.resumed) == 1
    assert copilot_runtime.deleted == []


@pytest.mark.django_db(transaction=True)
@pytest.mark.asyncio
async def test_async_prompt_uses_user(copilot_runtime, django_user_model):
    user = await django_user_model.objects.acreate(username="ana")
    copilot_runtime.agent("support").replies("summary")
    assert await SupportAgent(user=user).asummarize(7) == "summary"
    assert copilot_runtime.prompts("support") == ["Summarize order 7 for ana."]
