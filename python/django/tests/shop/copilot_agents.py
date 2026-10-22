from pydantic import BaseModel

from copilot_django import CopilotAgent, CopilotReply, policies, prompt

from .copilot_tools import lookup_order, refund_order


class Decision(BaseModel):
    approve: bool
    reason: str


class SupportAgent(CopilotAgent):
    instructions = "You help customers with shop orders."
    tools = [lookup_order, refund_order, "count_orders"]
    mcp_servers = ["docs"]
    permissions = policies.allow_registered_tools()

    @prompt("Should we refund order {{ order_id }}? Customer says: {{ message }}")
    def decide_refund(self, order_id: int, message: str) -> Decision: ...

    @prompt("Summarize order {{ order_id }} for {{ user.username }}.")
    async def asummarize(self, order_id: int) -> str: ...

    @prompt(template_name="shop/greeting.txt")
    def greet(self) -> CopilotReply: ...

    @prompt("Hello {{ missing }}")
    def broken(self) -> str: ...


class LockedAgent(CopilotAgent):
    """Default policy: denies every tool."""

    tools = [lookup_order]
