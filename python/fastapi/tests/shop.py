"""A tiny application used by the tests: agents, tools, and dependencies."""

from __future__ import annotations

from dataclasses import dataclass

from fastapi import Header, HTTPException
from pydantic import BaseModel

from copilot_fastapi import CopilotAgent, ToolContext, copilot_tool, policies, prompt

ORDERS = {1: "shipped", 2: "processing"}


@dataclass
class User:
    id: int
    name: str
    staff: bool = False


USERS = {"ana": User(1, "Ana", staff=True), "bob": User(2, "Bob")}


def current_user(x_user: str | None = Header(default=None)) -> User | None:
    if x_user is None:
        return None
    if x_user not in USERS:
        raise HTTPException(401, "unknown user")
    return USERS[x_user]


class Db:
    def __init__(self) -> None:
        self.queries: list[str] = []


def get_db() -> Db:
    return Db()


@copilot_tool
def lookup_order(order_id: int, ctx: ToolContext) -> str:
    """Look up an order's status."""
    db = ctx.agent.attributes.get("db")
    if db is not None:
        db.queries.append(f"order:{order_id}")
    if order_id not in ORDERS:
        raise HTTPException(404, f"Order {order_id} not found")
    return ORDERS[order_id]


@copilot_tool(requires=lambda ctx: ctx.user is not None and ctx.user.staff)
def refund_order(order_id: int) -> str:
    """Refund an order (staff only)."""
    return f"refunded {order_id}"


@copilot_tool
def broken() -> str:
    """Always fails with a server error."""
    raise HTTPException(500, "database exploded")


class Triage(BaseModel):
    priority: str
    summary: str


class SupportAgent(CopilotAgent):
    instructions = "You help customers with shop orders."
    tools = [lookup_order, refund_order, "tests.shop.broken"]
    permissions = policies.allow_registered_tools()

    @prompt("Where is order {order_id}? Asked by {user.name}.")
    def where_is(self, order_id: int) -> str: ...

    @prompt("Where is order {order_id}?")
    async def awhere_is(self, order_id: int) -> str: ...

    @prompt("Triage: {text}")
    async def triage(self, text: str) -> Triage: ...

    @prompt("Continue: {text}")
    async def chat(self, text: str, conversation_id: str) -> str: ...


class LockedAgent(CopilotAgent):
    tools = [lookup_order]
