import asyncio
from datetime import UTC, datetime, timedelta
from typing import Annotated

import pytest
from fastapi import Depends, FastAPI, HTTPException
from fastapi.testclient import TestClient
from pydantic import BaseModel, ValidationError

from copilot_fastapi import Copilot, CopilotSessionStore, CopilotSettings

from tests.shop import USERS, SupportAgent, current_user

OLD = datetime.now(UTC) - timedelta(days=30)


class Question(BaseModel):
    text: str


def make_app(copilot: Copilot) -> FastAPI:
    app = FastAPI(lifespan=copilot.lifespan, exception_handlers=copilot.exception_handlers)
    Support = Annotated[SupportAgent, copilot.depends(SupportAgent)]
    Sessions = Annotated[CopilotSessionStore, Depends(copilot.session_store)]

    @app.post("/chats/{chat_id}")
    async def chat(chat_id: str, q: Question, agent: Support) -> dict:
        return {"answer": await agent.conversation(chat_id).asend(q.text)}

    @app.get("/chats/{chat_id}")
    async def history(chat_id: str, agent: Support, sessions: Sessions) -> list[dict]:
        messages = await sessions.aget_messages(sessions.session_id_for(agent, chat_id))
        if messages is None:
            raise HTTPException(404)
        return [{"role": m.role, "content": m.content} for m in messages]

    @app.delete("/chats/{chat_id}")
    async def delete(chat_id: str, agent: Support, sessions: Sessions) -> dict:
        return {"deleted": await sessions.adelete(sessions.session_id_for(agent, chat_id))}

    return app


def test_store_dependency_reads_and_deletes_history(copilot, fake):
    fake.agent("support").replies("Shipped")
    with TestClient(make_app(copilot)) as client:
        ana = {"x-user": "ana"}
        assert client.post("/chats/c1", json={"text": "Where is 1?"}, headers=ana).json() == {"answer": "Shipped"}
        assert client.get("/chats/c1", headers=ana).json() == [
            {"role": "user", "content": "Where is 1?"},
            {"role": "assistant", "content": "Shipped"},
        ]
        assert client.get("/chats/c1", headers={"x-user": "bob"}).status_code == 404
        assert client.delete("/chats/c1", headers=ana).json() == {"deleted": True}
        assert client.delete("/chats/c1", headers=ana).json() == {"deleted": False}
    assert fake.sessions() == []


def test_session_id_for_accepts_classes_instances_and_names(copilot):
    store = copilot.session_store()
    assert store is copilot.session_store()
    ana = USERS["ana"]
    agent = copilot.get(SupportAgent, user=ana)
    expected = agent.conversation("c1").session_id
    assert store.session_id_for(agent, "c1") == expected
    assert store.session_id_for(SupportAgent, "c1", user=ana) == expected
    assert store.session_id_for("support", "c1", user=ana) == expected
    assert store.session_id_for(SupportAgent, "c1") != expected
    with pytest.raises(LookupError):
        store.session_id_for("nobody", "c1")


def test_store_deletes_isolated_workspace(copilot, fake):
    fake.add_session("agent-x")
    workspace = copilot.settings.base_directory / "workspaces" / "agent-x"
    workspace.mkdir(parents=True)
    assert copilot.session_store().delete("agent-x")
    assert not workspace.exists()


def test_cleanup_is_disabled_by_default(copilot):
    assert copilot.settings.sessions.max_age is None
    assert copilot.session_cleanup() is None


def test_session_settings(monkeypatch):
    monkeypatch.setenv("COPILOT_SESSIONS__MAX_AGE", "P7D")
    monkeypatch.setenv("COPILOT_SESSIONS__CLEANUP_INTERVAL", "600")
    sessions = CopilotSettings().sessions
    assert sessions.max_age == timedelta(days=7)
    assert sessions.cleanup_interval == timedelta(minutes=10)
    with pytest.raises(ValidationError, match="positive"):
        CopilotSettings(sessions={"max_age": 0})


def test_cleanup_interval_defaults_to_half_max_age(settings):
    settings.sessions.max_age = timedelta(minutes=30)
    cleanup = Copilot(settings).session_cleanup()
    assert cleanup.max_age == timedelta(minutes=30)
    assert cleanup.interval == timedelta(minutes=15)


async def test_lifespan_runs_cleanup_when_enabled(settings):
    settings.sessions.max_age = timedelta(days=7)
    settings.sessions.cleanup_interval = timedelta(milliseconds=10)
    copilot = Copilot(settings, agents=[SupportAgent], current_user=current_user)
    with copilot.test_runtime() as fake:
        fake.add_session("agent-old", modified=OLD)
        fake.add_session("agent-new")
        fake.add_session("not-ours", modified=OLD)
        async with copilot.lifespan():
            await asyncio.sleep(0.05)
            assert sorted(fake.sessions()) == ["agent-new", "agent-old", "not-ours"]  # runtime not started
            await copilot.runtime.run_async(copilot.runtime.client())
            for _ in range(200):
                if "agent-old" not in fake.sessions():
                    break
                await asyncio.sleep(0.01)
        assert sorted(fake.sessions()) == ["agent-new", "not-ours"]
