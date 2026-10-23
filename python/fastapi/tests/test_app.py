from contextlib import asynccontextmanager
from typing import Annotated

import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient
from pydantic import BaseModel

from copilot_fastapi import Copilot, CopilotAgent, CopilotConfigurationError, CopilotSettings

from tests.shop import Db, SupportAgent, get_db


class Question(BaseModel):
    text: str


def make_app(copilot: Copilot) -> FastAPI:
    app = FastAPI(lifespan=copilot.lifespan, exception_handlers=copilot.exception_handlers)
    Support = Annotated[SupportAgent, copilot.depends(SupportAgent)]
    SupportWithDb = Annotated[SupportAgent, copilot.depends(SupportAgent, db=Depends(get_db))]

    @app.post("/ask")
    async def ask(q: Question, agent: Support) -> dict:
        return {"answer": await agent.aask(q.text)}

    @app.post("/ask-sync")
    def ask_sync(q: Question, agent: Support) -> dict:
        return {"answer": agent.ask(q.text)}

    @app.post("/orders/{order_id}")
    async def order(order_id: int, agent: SupportWithDb) -> dict:
        answer = await agent.awhere_is(order_id)
        return {"answer": answer, "queries": agent.attributes["db"].queries}

    @app.post("/chat/{conversation_id}")
    async def chat(conversation_id: str, q: Question, agent: Support) -> dict:
        reply = await agent.conversation(conversation_id).asend(q.text)
        return {"answer": reply, "session": agent.conversation(conversation_id).session_id}

    @app.post("/resume/{conversation_id}")
    async def resume(conversation_id: str, q: Question, agent: Support) -> dict:
        return {"answer": await (await agent.aresume(conversation_id)).asend(q.text)}

    app.include_router(copilot.router())
    return app


@pytest.fixture
def client(copilot, fake):
    with TestClient(make_app(copilot)) as test_client:
        yield test_client


def test_async_and_sync_endpoints(client, fake):
    fake.agent("support").replies("a", "b")
    assert client.post("/ask", json={"text": "hi"}).json() == {"answer": "a"}
    assert client.post("/ask-sync", json={"text": "yo"}).json() == {"answer": "b"}
    assert fake.prompts("support") == ["hi", "yo"]
    assert fake.sessions("support") == []  # one-shot sessions are deleted


def test_dependencies_reach_tools(client, fake):
    fake.agent("support").calls_tool("lookup_order", {"order_id": 1}).replies("Shipped")
    response = client.post("/orders/1")
    assert response.json() == {"answer": "Shipped", "queries": ["order:1"]}
    call = fake.tool_calls("support")[0]
    assert call.ok and call.text == "shipped"


def test_http_exceptions_become_tool_errors(client, fake):
    fake.agent("support").calls_tool("lookup_order", {"order_id": 99}).calls_tool("broken").replies("Sorry")
    client.post("/ask", json={"text": "where is 99"})
    missing, broken = fake.tool_calls("support")
    assert not missing.ok and "Order 99 not found" in missing.text
    assert not broken.ok and "database exploded" not in broken.text


def test_guards_use_the_current_user(client, fake):
    fake.agent("support").calls_tool("refund_order", {"order_id": 1}).replies("x")
    fake.agent("support").calls_tool("refund_order", {"order_id": 1}).replies("y")
    client.post("/ask", json={"text": "refund"}, headers={"X-User": "bob"})
    client.post("/ask", json={"text": "refund"}, headers={"X-User": "ana"})
    bob, ana = fake.tool_calls("support")
    assert not bob.ok and "Permission denied" in bob.text
    assert ana.ok and ana.text == "refunded 1"


def test_conversations_are_partitioned_by_user(client, fake):
    fake.agent("support").replies("1", "2", "3")
    ana = client.post("/chat/t1", json={"text": "a"}, headers={"X-User": "ana"}).json()
    again = client.post("/chat/t1", json={"text": "b"}, headers={"X-User": "ana"}).json()
    bob = client.post("/chat/t1", json={"text": "c"}, headers={"X-User": "bob"}).json()
    assert ana["session"] == again["session"] != bob["session"]
    assert fake.resumed == [ana["session"]]


def test_errors_map_to_status_codes(client, fake):
    assert client.post("/resume/nope", json={"text": "x"}).status_code == 404
    fake.agent("support").fails("boom")
    response = client.post("/ask", json={"text": "x"})
    assert response.status_code == 502
    assert "boom" not in response.text
    assert client.post("/ask", json={"text": "x"}, headers={"X-User": "eve"}).status_code == 401


def test_health(client):
    body = client.get("/copilot/health").json()
    assert body["ok"] and body["agents"] == ["locked", "support"]


def test_startup_fails_on_invalid_agents(settings):
    class BrokenAgent(CopilotAgent):
        tools = ["no.such.tool"]

    copilot = Copilot(settings, agents=[BrokenAgent])
    with pytest.raises(CopilotConfigurationError, match="no.such.tool"):
        with TestClient(FastAPI(lifespan=copilot.lifespan)):
            pass


def test_init_app_wraps_existing_lifespan(copilot, fake):
    events = []

    @asynccontextmanager
    async def lifespan(app):
        events.append("start")
        yield {"value": 1}
        events.append("stop")

    app = FastAPI(lifespan=lifespan)
    copilot.init_app(app)

    @app.post("/ask")
    async def ask(q: Question, agent: Annotated[SupportAgent, copilot.depends(SupportAgent)]) -> dict:
        return {"answer": await agent.aask(q.text)}

    fake.agent("support").replies("ok")
    with TestClient(app) as test_client:
        assert test_client.post("/ask", json={"text": "x"}).json() == {"answer": "ok"}
        fake.agent("support").fails()
        assert test_client.post("/ask", json={"text": "x"}).status_code == 502
        assert events == ["start"]
    assert events == ["start", "stop"]


def test_reserved_dependency_names(copilot):
    with pytest.raises(ValueError):
        copilot.depends(SupportAgent, user=Depends(lambda: None))


def test_db_dependency_type():
    assert isinstance(get_db(), Db)
