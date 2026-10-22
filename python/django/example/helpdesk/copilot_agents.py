from typing import Literal

from pydantic import BaseModel

from copilot_django import CopilotAgent, policies, prompt

from .copilot_tools import get_ticket, set_priority


class Triage(BaseModel):
    priority: Literal["low", "normal", "urgent"]
    summary: str


class TriageAgent(CopilotAgent):
    instructions = "You triage helpdesk tickets. Be concise and factual."
    tools = [get_ticket, set_priority]
    built_in_tools = False
    permissions = policies.allow_registered_tools()

    @prompt("Read ticket {{ ticket_id }}, set its priority, and summarize it in one sentence.")
    def triage(self, ticket_id: int) -> Triage: ...
