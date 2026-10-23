from datetime import UTC, datetime, timedelta

import pytest
from django.core.management import call_command
from django.core.management.base import CommandError
from django.test import override_settings

from copilot_django import get_session_store, sessions
from copilot_django.conf import get_settings, validate
from shop.copilot_agents import SupportAgent

OLD = datetime.now(UTC) - timedelta(days=30)


class User:
    is_authenticated = True

    def __init__(self, pk):
        self.pk = pk


def test_store_lists_reads_and_deletes_conversations(copilot_runtime):
    copilot_runtime.agent("support").replies("Shipped")
    alice = User(1)
    agent = SupportAgent(user=alice)
    agent.conversation("ticket-1").send("Where is 42?")

    store = get_session_store()
    assert store is get_session_store()
    session_id = store.session_id_for(SupportAgent, "ticket-1", user=alice)
    assert session_id == agent.conversation("ticket-1").session_id
    assert store.session_id_for(agent, "ticket-1") == session_id
    assert store.session_id_for("support", "ticket-1", user=alice) == session_id
    assert store.session_id_for(SupportAgent, "ticket-1") != session_id

    assert [s.session_id for s in store.list()] == [session_id]
    assert store.exists(session_id)
    assert [(m.role, m.content) for m in store.get_messages(session_id)] == [
        ("user", "Where is 42?"),
        ("assistant", "Shipped"),
    ]
    workspace = get_settings().base_directory / "workspaces" / session_id
    workspace.mkdir(parents=True, exist_ok=True)
    assert store.delete(session_id) is True
    assert not workspace.exists()
    assert store.delete(session_id) is False
    assert not agent.conversation("ticket-1").exists()


def test_cleanup_is_disabled_by_default():
    assert get_settings().SESSIONS["MAX_AGE"] is None
    assert sessions.get_session_cleanup() is None
    assert sessions.start_cleanup() is False
    assert sessions._sweeper is None


def test_cleanup_settings_and_background_thread():
    with override_settings(COPILOT={"SESSIONS": {"MAX_AGE": timedelta(hours=1)}}):
        cleanup = sessions.get_session_cleanup()
        assert cleanup.max_age == timedelta(hours=1)
        assert cleanup.interval == timedelta(minutes=30)
        sweeper = sessions._sweeper
        assert sweeper is not None and sweeper.thread.is_alive()
    sweeper.thread.join(2)
    assert not sweeper.thread.is_alive()
    assert sessions._sweeper is None

    manual = {"MAX_AGE": 600, "CLEANUP_INTERVAL": 120, "BACKGROUND_CLEANUP": False}
    with override_settings(COPILOT={"SESSIONS": manual}):
        cleanup = sessions.get_session_cleanup()
        assert cleanup.interval == timedelta(minutes=2)
        assert sessions._sweeper is None


def test_settings_are_validated():
    assert validate({"SESSIONS": {"MAX_AGE": 3600, "CLEANUP_INTERVAL": timedelta(minutes=5)}}) == []
    errors = validate({"SESSIONS": {"MAX_AGE": -1, "CLEANUP_INTERVAL": "soon", "BACKGROUND_CLEANUP": "yes", "TTL": 1}})
    assert len(errors) == 4


def test_cleanup_command(copilot_runtime, capsys):
    copilot_runtime.add_session("agent-old", modified=OLD)
    copilot_runtime.add_session("agent-new")
    copilot_runtime.add_session("someone-else", modified=OLD)
    with pytest.raises(CommandError, match="MAX_AGE"):
        call_command("copilot_cleanup_sessions")
    call_command("copilot_cleanup_sessions", "--max-age", str(7 * 24 * 3600))
    assert "Deleted 1 Copilot session(s)" in capsys.readouterr().out
    assert sorted(copilot_runtime.sessions()) == ["agent-new", "someone-else"]
