from copilot_django import ToolContext, ToolError, copilot_tool

from .models import Ticket


@copilot_tool
def get_ticket(ticket_id: int, ctx: ToolContext) -> dict:
    """Read a ticket the current user owns."""
    ticket = Ticket.objects.get(pk=ticket_id, owner=ctx.user)
    return {"id": ticket.pk, "title": ticket.title, "body": ticket.body, "priority": ticket.priority}


@copilot_tool(atomic=True)
def set_priority(ticket_id: int, priority: str, ctx: ToolContext) -> str:
    """Set a ticket's priority to low, normal, or urgent."""
    if priority not in dict(Ticket.PRIORITIES):
        raise ToolError("Priority must be low, normal, or urgent.")
    updated = Ticket.objects.filter(pk=ticket_id, owner=ctx.user).update(priority=priority)
    if not updated:
        raise ToolError("Not found.")
    return f"Priority set to {priority}."
