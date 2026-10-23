---
title: Documentation
---

# Copilot Agents for FastAPI

`copilot-fastapi` connects the
[GitHub Copilot SDK for Python](https://github.com/github/copilot-sdk/tree/main/python) to
FastAPI. Declare agents as classes, inject them into endpoints with `Depends`, and turn your
application functions into tools, from both `def` and `async def` endpoints.

> **Status:** preview. Milestone M1 of the
> [design](https://github.com/brunoborges/copilot-sdk-integrations/blob/main/python/fastapi/DESIGN.md)
> is implemented and tested, but the packages are not on PyPI yet. Install them from the
> [repository](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/python). See
> [Getting started](getting-started.md).

```python
class SupportAgent(CopilotAgent):
    instructions = "You help customers with their orders."
    tools = [lookup_order]                         # @copilot_tool functions
    permissions = policies.allow_registered_tools()

    @prompt("Triage this message: {text}")
    async def triage(self, text: str) -> Triage: ...


copilot = Copilot(agents=[SupportAgent], current_user=get_current_user)
app = FastAPI(lifespan=copilot.lifespan, exception_handlers=copilot.exception_handlers)

Support = Annotated[SupportAgent, copilot.depends(SupportAgent, db=Depends(get_db))]


@app.post("/triage")
async def triage(text: str, agent: Support) -> Triage:
    return await agent.triage(text)                # typed pydantic result
```

## What you get

- **Injectable agents**: [`CopilotAgent`](agents.md) subclasses are bound to the current user
  and request by `copilot.depends(...)`, and can be overridden per environment in settings.
- **Typed prompts**: [`@prompt`](prompts.md) methods render a `str.format` template and parse
  the reply into the return annotation (`str` or a pydantic model).
- **Tools that reuse your code**: [`@copilot_tool`](tools.md) functions see the current user and
  your own dependencies, and a 4xx `HTTPException` becomes an error the model can read.
- **Deny-by-default permissions**: [policies](permissions.md) decide which tools, MCP servers
  and built-in tool kinds the agent may use.
- **Per-user conversations**: [conversations](conversations.md) are partitioned by the current
  user, so one user can't resume another user's conversation.
- **Operations**: [startup validation, error mapping and a health route](operations.md).
- **Testing**: a [scripted in-memory runtime](testing.md) that needs no CLI or network access.

## Packages

| Package | Import | Purpose |
|---|---|---|
| `copilot-agent-core` | `copilot_agents` | Framework-neutral agent layer, shared with the Django integration. |
| `copilot-fastapi` | `copilot_fastapi` | The FastAPI extension: agents, dependencies, tools, settings, test helpers. |

## Not yet implemented

Streaming responses (SSE), interactive approvals, and OpenTelemetry tracing are planned for
later milestones (M2–M4).
