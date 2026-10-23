from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime, timedelta

import pytest
from copilot.generated.session_events import (
    AssistantMessageData,
    SessionEvent,
    SessionEventType,
    ToolExecutionStartData,
    UserMessageData,
)

from copilot_agents import (
    SHARED,
    AgentDefinition,
    AgentInvoker,
    SessionCleanup,
    SessionStore,
    ToolContext,
    derive_session_id,
    tool,
)
from copilot_agents.sessions import default_interval

NOW = datetime(2026, 1, 10, 12, 0, tzinfo=UTC)
OLD = NOW - timedelta(days=30)


def event(kind, data, agent_id=None):
    return SessionEvent(data=data, id=uuid.uuid4(), timestamp=NOW, type=kind, agent_id=agent_id)


class TeamResolver:
    def resolve(self, identity):
        return "tok"

    def partition_key(self, identity):
        return "team"


def make_invoker(runtime, **kwargs):
    kwargs.setdefault("name", "support")
    kwargs.setdefault("permissions", "allow_registered_tools")
    return AgentInvoker(AgentDefinition(**kwargs), runtime)


@pytest.fixture
def store(runtime):
    return SessionStore(runtime)


def started(runtime):
    runtime.call(lambda client: asyncio.sleep(0))


# -- store ----------------------------------------------------------------------------


def test_list_skips_remote_sessions_and_orders_by_modified(fake, store):
    fake.add_session("agent-a", modified=NOW - timedelta(days=2))
    fake.add_session("agent-b", modified=NOW)
    fake.add_session("remote", modified=NOW, is_remote=True)
    fake.add_session("other", modified=NOW - timedelta(days=1))
    assert [s.session_id for s in store.list()] == ["agent-b", "other", "agent-a"]


def test_get_and_exists(fake, store):
    fake.add_session("agent-a", summary="Order help")
    assert store.get("agent-a").summary == "Order help"
    assert store.exists("agent-a")
    assert store.get("missing") is None
    assert not store.exists("missing")
    with pytest.raises(ValueError):
        store.get("")


def test_messages_skip_sub_agents_tools_and_empty_replies(fake, store):
    fake.add_session(
        "agent-a",
        events=[
            event(SessionEventType.USER_MESSAGE, UserMessageData(content="Hi", message_id="u1")),
            event(SessionEventType.USER_MESSAGE, UserMessageData(content="sub"), agent_id="explore"),
            event(
                SessionEventType.TOOL_EXECUTION_START,
                ToolExecutionStartData(tool_call_id="c1", tool_name="lookup"),
            ),
            event(SessionEventType.ASSISTANT_MESSAGE, AssistantMessageData(content="", message_id="a0")),
            event(SessionEventType.ASSISTANT_MESSAGE, AssistantMessageData(content="sub", message_id="s"), "x"),
            event(SessionEventType.ASSISTANT_MESSAGE, AssistantMessageData(content="Hello", message_id="a1")),
        ],
    )
    messages = store.get_messages("agent-a")
    assert [(m.role, m.content, m.message_id) for m in messages] == [("user", "Hi", "u1"), ("assistant", "Hello", "a1")]
    assert messages[0].timestamp == NOW
    assert store.get_messages("missing") is None


def test_messages_page_through_history(fake, store, monkeypatch):
    monkeypatch.setattr("copilot_agents.sessions.PAGE_SIZE", 2)
    texts = [f"m{i}" for i in range(5)]
    fake.add_session(
        "agent-a",
        events=[event(SessionEventType.USER_MESSAGE, UserMessageData(content=t)) for t in texts],
    )
    assert [m.content for m in store.get_messages("agent-a")] == texts


def test_conversation_history_is_read_without_resuming(fake, runtime, store):
    invoker = make_invoker(runtime)
    fake.agent("support").replies("Shipped")
    invoker.invoke("Where is 42?", conversation_id="c1")
    session_id = store.session_id_for(invoker, "c1")
    fake.resumed.clear()
    messages = store.get_messages(session_id)
    assert [(m.role, m.content) for m in messages] == [("user", "Where is 42?"), ("assistant", "Shipped")]
    assert fake.resumed == []


def test_delete(fake, store):
    removed = []
    store.on_deleted = removed.append
    fake.add_session("agent-a")
    assert store.delete("agent-a") is True
    assert store.delete("agent-a") is False
    assert fake.sessions() == []
    assert removed == ["agent-a"]


def test_async_api(fake, store):
    asyncio.run(_async_api(fake, store))


async def _async_api(fake, store):
    fake.add_session("agent-a")
    assert [s.session_id for s in await store.alist()] == ["agent-a"]
    assert await store.aexists("agent-a")
    assert (await store.aget("agent-a")).session_id == "agent-a"
    assert await store.aget_messages("agent-a") == []
    assert await store.adelete("agent-a")
    assert not await store.aexists("agent-a")


def test_sync_api_refuses_running_loop(store):
    async def main():
        with pytest.raises(RuntimeError, match="alist"):
            store.list()

    asyncio.run(main())


def test_delete_waits_for_turn_in_flight(fake, runtime, store):
    asyncio.run(_delete_waits(fake, runtime, store))


async def _delete_waits(fake, runtime, store):
    fake.add_session("agent-a")
    release = None

    async def run_turn():
        nonlocal release
        release = asyncio.Event()
        await runtime.activity.track("agent-a", release.wait())

    # Simulate a turn running on the Copilot loop.
    turn_future = runtime.submit(run_turn())
    await asyncio.sleep(0.05)
    delete = asyncio.ensure_future(store.adelete("agent-a"))
    await asyncio.sleep(0.05)
    assert not delete.done()
    assert fake.sessions() == ["agent-a"]
    runtime.submit(_set(release))
    assert await delete is True
    await asyncio.wrap_future(turn_future)


async def _set(event):
    event.set()


def test_session_id_for(runtime, store):
    invoker = make_invoker(runtime)
    definition = invoker.definition
    expected = derive_session_id(definition.type_name, "support", "c1", SHARED)
    assert store.session_id_for(invoker, "c1") == expected
    assert store.session_id_for(definition, "c1") == expected
    assert store.session_id_for(invoker, "c1") == invoker.session_id("c1")
    partitioned = derive_session_id(definition.type_name, "support", "c1", "alice")
    assert store.session_id_for(definition, "c1", partition_key="alice") == partitioned

    per_team = make_invoker(runtime, token_resolver=TeamResolver())
    assert store.session_id_for(per_team, "c1") == derive_session_id(definition.type_name, "support", "c1", "team")
    with pytest.raises(ValueError):
        store.session_id_for(invoker, "")
    with pytest.raises(TypeError):
        store.session_id_for(object(), "c1")


# -- cleanup --------------------------------------------------------------------------


def cleanup(store, max_age=timedelta(days=7), **kwargs):
    return SessionCleanup(store, max_age, clock=lambda: NOW, **kwargs)


def test_interval_defaults_to_half_max_age_clamped():
    assert default_interval(timedelta(minutes=30)) == timedelta(minutes=15)
    assert default_interval(timedelta(seconds=10)) == timedelta(minutes=1)
    assert default_interval(timedelta(days=7)) == timedelta(hours=1)
    assert cleanup(SessionStore(None), 3600).interval == timedelta(minutes=30)  # type: ignore[arg-type]
    assert cleanup(SessionStore(None), 3600, interval=5).interval == timedelta(seconds=5)  # type: ignore[arg-type]
    with pytest.raises(ValueError):
        cleanup(SessionStore(None), 0)  # type: ignore[arg-type]


def test_sweep_deletes_only_expired_managed_local_sessions(fake, runtime, store):
    removed = []
    store.on_deleted = removed.append
    fake.add_session("agent-old", modified=OLD)
    fake.add_session("agent-new", modified=NOW - timedelta(days=1))
    fake.add_session("agent-remote", modified=OLD, is_remote=True)
    fake.add_session("user-session", modified=OLD)
    started(runtime)
    assert cleanup(store).sweep() == 1
    assert sorted(fake.sessions()) == ["agent-new", "agent-remote", "user-session"]
    assert removed == ["agent-old"]


def test_sweep_skips_when_runtime_not_started(fake, runtime, store):
    fake.add_session("agent-old", modified=OLD)
    assert cleanup(store).sweep() == 0
    assert runtime.status() == "stopped"
    assert fake.sessions() == ["agent-old"]
    assert cleanup(store).sweep(start_runtime=True) == 1


def test_sweep_rechecks_metadata_before_deleting(fake, runtime, store):
    fake.add_session("agent-old", modified=OLD)
    started(runtime)
    client = runtime.call(lambda c: _identity(c))
    original = client.list_sessions

    async def stale_list(filter=None):
        sessions = await original()
        fake.touch("agent-old", NOW)
        return sessions

    client.list_sessions = stale_list
    assert cleanup(store).sweep() == 0
    assert fake.sessions() == ["agent-old"]


def test_sweep_skips_sessions_with_turn_in_flight(fake, runtime, store):
    fake.add_session("agent-busy", modified=OLD)
    fake.add_session("agent-idle", modified=OLD)
    started(runtime)
    runtime.activity._turns["agent-busy"] = 1
    assert cleanup(store).sweep() == 1
    assert fake.sessions() == ["agent-busy"]
    del runtime.activity._turns["agent-busy"]
    assert cleanup(store).sweep() == 1


def test_sweep_continues_after_delete_failure(fake, runtime, store, caplog):
    fake.add_session("agent-a", modified=OLD)
    fake.add_session("agent-b", modified=OLD)
    started(runtime)
    client = runtime.call(lambda c: _identity(c))
    original = client.delete_session

    async def flaky(session_id):
        if session_id == "agent-a":
            raise RuntimeError("disk full")
        await original(session_id)

    client.delete_session = flaky
    assert cleanup(store).sweep() == 1
    assert fake.sessions() == ["agent-a"]
    assert "agent-a" in caplog.text
    assert not runtime.activity.busy("agent-a")


def test_run_sweeps_periodically_and_survives_errors(fake, runtime, store, caplog):
    asyncio.run(_run_sweeps(fake, runtime, store, caplog))


async def _run_sweeps(fake, runtime, store, caplog):
    fake.add_session("agent-old", modified=OLD)
    await runtime.run_async(runtime.client())
    job = cleanup(store, interval=0.01)
    calls = []
    original = job.asweep

    async def asweep():
        calls.append(1)
        if len(calls) == 1:
            raise RuntimeError("boom")
        return await original()

    job.asweep = asweep  # type: ignore[method-assign]
    task = asyncio.ensure_future(job.run())
    for _ in range(100):
        if len(calls) >= 2:
            break
        await asyncio.sleep(0.01)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert "retrying" in caplog.text
    assert fake.sessions() == []


async def _identity(client):
    return client



in_flight: list[bool] = []


@tool
def probe(ctx: ToolContext) -> str:
    """Report whether the session is marked in flight."""
    in_flight.append(ctx.agent.attributes["runtime"].activity.busy(ctx.agent.session_id))
    return "ok"


def test_invoker_marks_turns_in_flight(fake, runtime):
    invoker = make_invoker(runtime, tools=[probe])
    fake.agent("support").calls_tool("probe").replies("done")
    in_flight.clear()
    assert invoker.invoke("hi", conversation_id="c1", attributes={"runtime": runtime}) == "done"
    assert in_flight == [True]
    assert not runtime.activity.busy(invoker.session_id("c1"))
