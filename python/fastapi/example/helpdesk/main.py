"""Helpdesk API: run with ``uvicorn helpdesk.main:app`` (send ``X-User: ana``)."""

from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel

from copilot_fastapi import Copilot

from .agents import Triage, TriageAgent
from .store import Store, Ticket, get_store, store


def current_user(x_user: Annotated[str | None, Header()] = None) -> str:
    """Demo authentication; use your real OAuth2/session dependency instead."""
    if not x_user:
        raise HTTPException(401, "Send an X-User header.")
    return x_user


copilot = Copilot(agents=[TriageAgent], current_user=current_user)
app = FastAPI(title="Helpdesk", lifespan=copilot.lifespan, exception_handlers=copilot.exception_handlers)
app.include_router(copilot.router())

Agent = Annotated[TriageAgent, copilot.depends(TriageAgent, store=Depends(get_store))]



class Message(BaseModel):
    message: str


def owned(ticket_id: int, user: str, tickets: Store) -> Ticket:
    ticket = tickets.get(ticket_id, user)
    if ticket is None:
        raise HTTPException(404, "Ticket not found.")
    return ticket


@app.post("/tickets/{ticket_id}/triage")
async def triage(ticket_id: int, agent: Agent, tickets: Annotated[Store, Depends(get_store)]) -> Triage:
    owned(ticket_id, agent.user, tickets)
    return await agent.triage(ticket_id)


@app.post("/tickets/{ticket_id}/chat")
async def chat(ticket_id: int, body: Message, agent: Agent, tickets: Annotated[Store, Depends(get_store)]) -> dict:
    owned(ticket_id, agent.user, tickets)
    answer = await agent.conversation(f"ticket-{ticket_id}").asend(body.message)
    return {"answer": answer}


if not store.tickets:
    store.add(Ticket(1, "ana", "Checkout is down", "Customers get a 500 when paying by card."))
