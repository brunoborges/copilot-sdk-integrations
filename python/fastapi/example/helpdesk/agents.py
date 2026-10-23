from typing import Literal

from fastapi import HTTPException
from pydantic import BaseModel

from copilot_fastapi import CopilotAgent, ToolContext, ToolError, copilot_tool, policies, prompt

from .store import PRIORITIES, Store, Ticket


def _ticket(ctx: ToolContext, ticket_id: int) -> Ticket:
    store: Store = ctx.agent.attributes["store"]
    ticket = store.get(ticket_id, ctx.user)
    if ticket is None:
        raise HTTPException(404, "Ticket not found.")  # becomes a tool error the model can read
    return ticket


@copilot_tool
def get_ticket(ticket_id: int, ctx: ToolContext) -> dict:
    """Read a ticket the current user owns."""
    ticket = _ticket(ctx, ticket_id)
    return {"id": ticket.id, "title": ticket.title, "body": ticket.body, "priority": ticket.priority}


@copilot_tool
def set_priority(ticket_id: int, priority: str, ctx: ToolContext) -> str:
    """Set a ticket's priority to low, normal, or urgent."""
    if priority not in PRIORITIES:
        raise ToolError("Priority must be low, normal, or urgent.")
    _ticket(ctx, ticket_id).priority = priority
    return f"Priority set to {priority}."


class Triage(BaseModel):
    priority: Literal["low", "normal", "urgent"]
    summary: str


class TriageAgent(CopilotAgent):
    instructions = "You triage helpdesk tickets. Be concise and factual."
    tools = [get_ticket, set_priority]
    built_in_tools = False
    permissions = policies.allow_registered_tools()

    @prompt("Read ticket {ticket_id}, set its priority, and summarize it in one sentence.")
    async def triage(self, ticket_id: int) -> Triage: ...
