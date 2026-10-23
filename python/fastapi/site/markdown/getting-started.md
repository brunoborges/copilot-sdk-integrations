---
title: Getting started
---

# Getting started

## Requirements

- Python 3.11 or later and FastAPI 0.115 or later.
- The Copilot runtime. The Python SDK downloads it on first use; production images should
  download it at build time with `python -m copilot download-runtime`.
- A GitHub identity with Copilot access: a `GITHUB_TOKEN`, a signed-in runtime
  (`copilot login`), or a [per-user token resolver](conversations.md#per-user-tokens).

## Install

The packages are not on PyPI yet. Install them from a clone:

```sh
git clone https://github.com/brunoborges/copilot-sdk-integrations
pip install -e copilot-sdk-integrations/python/core -e copilot-sdk-integrations/python/fastapi
```

## Configure

Settings are read from `COPILOT_*` environment variables and `.env`:

```sh
COPILOT_AUTH__GITHUB_TOKEN=ghp_...
COPILOT_DEFAULTS__MODEL=gpt-5
```

See [Configuration](configuration.md) for every setting.

## Add a tool

```python
# app/agents.py
from fastapi import HTTPException
from copilot_fastapi import ToolContext, copilot_tool


@copilot_tool
def lookup_order(order_id: int, ctx: ToolContext) -> dict:
    """Look up one of the current user's orders."""
    db = ctx.agent.attributes["db"]
    order = db.get_order(order_id, owner=ctx.user.id)
    if order is None:
        raise HTTPException(404, "Order not found")       # the model sees "Order not found"
    return {"id": order.id, "status": order.status}
```

## Add an agent

```python
# app/agents.py
from copilot_fastapi import CopilotAgent, policies, prompt


class SupportAgent(CopilotAgent):
    instructions = "You answer questions about the user's orders."
    tools = [lookup_order]
    built_in_tools = False
    permissions = policies.allow_registered_tools()

    @prompt("Summarize the status of order {order_id} in one sentence.")
    async def summarize(self, order_id: int) -> str: ...
```

## Wire it into the app

```python
# app/main.py
from typing import Annotated
from fastapi import Depends, FastAPI
from copilot_fastapi import Copilot

from .agents import SupportAgent
from .auth import get_current_user
from .db import get_db

copilot = Copilot(agents=[SupportAgent], current_user=get_current_user)
app = FastAPI(lifespan=copilot.lifespan, exception_handlers=copilot.exception_handlers)
app.include_router(copilot.router())                     # GET /copilot/health

Support = Annotated[SupportAgent, copilot.depends(SupportAgent, db=Depends(get_db))]


@app.get("/orders/{order_id}/summary")
async def summary(order_id: int, agent: Support) -> dict:
    return {"summary": await agent.summarize(order_id)}


@app.post("/chat/{conversation_id}")
async def chat(conversation_id: str, message: str, agent: Support) -> dict:
    return {"answer": await agent.conversation(conversation_id).asend(message)}
```

If the app already has its own lifespan, call `copilot.init_app(app)` instead. It runs the
Copilot lifespan around yours and installs the exception handlers.

## Check the setup

```sh
uvicorn app.main:app
curl 'localhost:8000/copilot/health?deep=true'
```

Startup fails with a `CopilotConfigurationError` listing every problem, such as unknown tools or
MCP servers. The deep health check starts the runtime and confirms authentication.
