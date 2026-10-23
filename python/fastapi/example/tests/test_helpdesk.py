import pytest
from fastapi.testclient import TestClient

from copilot_fastapi.testing import copilot_test_runtime
from helpdesk.main import app, copilot
from helpdesk.store import Ticket, store


@pytest.fixture(autouse=True)
def tickets():
    store.tickets.clear()
    store.add(Ticket(1, "ana", "Checkout is down", "Customers get a 500 when paying by card."))
    store.add(Ticket(2, "bob", "Printer", "Out of toner."))


@pytest.fixture
def fake():
    with copilot_test_runtime(copilot) as runtime:
        yield runtime


@pytest.fixture
def client(fake):
    with TestClient(app) as test_client:
        yield test_client


def test_triage_sets_priority(client, fake):
    (
        fake.agent("triage")
        .calls_tool("get_ticket", {"ticket_id": 1})
        .calls_tool("set_priority", {"ticket_id": 1, "priority": "urgent"})
        .replies_json({"priority": "urgent", "summary": "Card payments fail at checkout."})
    )
    response = client.post("/tickets/1/triage", headers={"X-User": "ana"})
    assert response.json() == {"priority": "urgent", "summary": "Card payments fail at checkout."}
    assert store.tickets[1].priority == "urgent"
    assert all(call.ok for call in fake.tool_calls("triage"))


def test_tools_only_see_the_callers_tickets(client, fake):
    fake.agent("triage").calls_tool("get_ticket", {"ticket_id": 1}).replies("Nothing found.")
    response = client.post("/tickets/2/chat", json={"message": "Look at ticket 1"}, headers={"X-User": "bob"})
    assert response.json() == {"answer": "Nothing found."}
    call = fake.tool_calls("triage")[0]
    assert not call.ok and "Ticket not found." in call.text


def test_conversations_continue(client, fake):
    fake.agent("triage").replies("Hi", "Still here")
    client.post("/tickets/1/chat", json={"message": "hello"}, headers={"X-User": "ana"})
    client.post("/tickets/1/chat", json={"message": "again"}, headers={"X-User": "ana"})
    assert len(fake.created) == 1 and len(fake.resumed) == 1


def test_requires_a_user_and_ownership(client):
    assert client.post("/tickets/1/triage").status_code == 401
    assert client.post("/tickets/1/triage", headers={"X-User": "bob"}).status_code == 404
