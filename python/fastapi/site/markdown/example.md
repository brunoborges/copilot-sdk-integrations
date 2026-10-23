---
title: Example project
---

# Example project

[`python/fastapi/example`](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/python/fastapi/example)
is a small helpdesk API. A `TriageAgent` reads a ticket, sets its priority, and returns a typed
summary.

```python
# helpdesk/agents.py
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
```

```python
# helpdesk/main.py
copilot = Copilot(agents=[TriageAgent], current_user=current_user)
app = FastAPI(title="Helpdesk", lifespan=copilot.lifespan, exception_handlers=copilot.exception_handlers)
app.include_router(copilot.router())

Agent = Annotated[TriageAgent, copilot.depends(TriageAgent, store=Depends(get_store))]
```

The API exposes `POST /tickets/{id}/triage` (a typed one-shot call) and
`POST /tickets/{id}/chat` (a per-ticket conversation). The ticket store is injected as a
dependency, and the tools only read and update tickets owned by the current user. Users are
identified by an `X-User` header for the demo; use your real authentication dependency instead.

## Run it

```sh
cd python
uv venv && uv pip install -e core -e fastapi uvicorn
python -m copilot download-runtime
cd fastapi/example
GITHUB_TOKEN=... ../../.venv/bin/uvicorn helpdesk.main:app
curl -XPOST -H 'X-User: ana' localhost:8000/tickets/1/triage
```

## Test it

```sh
uv pip install pytest pytest-asyncio httpx
../../.venv/bin/python -m pytest
```

The tests script the agent to call both tools and reply with JSON, then check that the ticket's
priority was really updated in the store and that other users can't triage the ticket.
