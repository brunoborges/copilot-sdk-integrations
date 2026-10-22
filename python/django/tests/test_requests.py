import pytest

from copilot_agents import derive_session_id


@pytest.mark.django_db
def test_middleware_supplies_user_and_partition(client, copilot_runtime, django_user_model):
    user = django_user_model.objects.create_user("ana")
    client.force_login(user)
    copilot_runtime.agent("support").replies("hello ana")
    data = client.get("/ask/", {"q": "hi"}).json()
    assert data["answer"] == "hello ana"
    assert data["session"] == derive_session_id("shop.copilot_agents.SupportAgent", "support", "chat", str(user.pk))
    assert copilot_runtime.sessions("support") == [data["session"]]


@pytest.mark.django_db
def test_anonymous_requests_share_the_partition(client, copilot_runtime):
    copilot_runtime.agent("support").replies("hi")
    data = client.get("/ask/", {"q": "hi"}).json()
    assert data["session"] == derive_session_id("shop.copilot_agents.SupportAgent", "support", "chat")


@pytest.mark.django_db(transaction=True)
@pytest.mark.asyncio
async def test_async_view(async_client, copilot_runtime, django_user_model):
    user = await django_user_model.objects.acreate(username="bob")
    await async_client.aforce_login(user)
    copilot_runtime.agent("support").replies("async hello")
    response = await async_client.get("/aask/", {"q": "hi"})
    assert response.json() == {"answer": "async hello"}
    expected = derive_session_id("shop.copilot_agents.SupportAgent", "support", "chat", str(user.pk))
    assert copilot_runtime.sessions("support") == [expected]
