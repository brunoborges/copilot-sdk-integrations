# copilot-fastapi

FastAPI integration for the [GitHub Copilot SDK for Python](https://pypi.org/project/github-copilot-sdk/).
You declare agents as classes and inject them into endpoints with `Depends`. Your
application functions become tools, so `HTTPException` and your own dependencies keep
working inside them. Agents work from both `def` and `async def` endpoints. The package
builds on [`copilot-agent-core`](../core/), the same engine that
[`copilot-django`](../django/) uses.

Status: **preview** (not yet on PyPI). Documentation: <https://brunoborges.github.io/copilot-sdk-integrations/fastapi/>
(source in [`site/`](site/)). [DESIGN.md](DESIGN.md) covers the scope and the
milestones. Streaming (SSE) and interactive approvals are planned.

## Quickstart

```python
# app/agents.py
from fastapi import HTTPException
from pydantic import BaseModel

from copilot_fastapi import CopilotAgent, ToolContext, copilot_tool, policies, prompt


@copilot_tool
def lookup_order(order_id: int, ctx: ToolContext) -> dict:
    """Look up an order the current user owns."""
    db = ctx.agent.attributes["db"]          # injected with copilot.depends(..., db=Depends(get_db))
    order = db.get_order(order_id, owner=ctx.user.id)
    if order is None:
        raise HTTPException(404, "Order not found")   # the model sees "Order not found"
    return order.as_dict()


class Triage(BaseModel):
    priority: str
    summary: str


class SupportAgent(CopilotAgent):            # name: "support"
    instructions = "You help customers with their orders."
    tools = [lookup_order]
    permissions = policies.allow_registered_tools()

    @prompt("Where is order {order_id}?")
    async def where_is(self, order_id: int) -> str: ...

    @prompt("Triage this message: {text}")
    async def triage(self, text: str) -> Triage: ...
```

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
app.include_router(copilot.router())        # GET /copilot/health

Support = Annotated[SupportAgent, copilot.depends(SupportAgent, db=Depends(get_db))]


@app.get("/orders/{order_id}/status")
async def status(order_id: int, agent: Support) -> dict:
    return {"answer": await agent.where_is(order_id)}


@app.post("/chat/{conversation_id}")
async def chat(conversation_id: str, message: str, agent: Support) -> dict:
    return {"answer": await agent.conversation(conversation_id).asend(message)}
```

If your app already has a lifespan, call `copilot.init_app(app)` instead. It runs the
Copilot lifespan around yours and installs the exception handlers.

## Features

- **Agents.** Subclass `CopilotAgent` and set any of these class attributes:
  `instructions`, `model`, `reasoning_effort`, `tools`, `mcp_servers`, `available_tools` /
  `excluded_tools`, `built_in_tools`, `permissions`, `hooks`, `working_directory`,
  `provider`, `token_resolver` and `timeout`. Every attribute can be overridden per
  environment through `COPILOT_AGENTS`. Agent names must be unique within a `Copilot`
  instance.
- **Dependency injection.** `copilot.depends(Agent, **deps)` gives you a per-request agent
  bound to the user returned by your `current_user` dependency and to the `Request`. Extra
  dependencies, such as a database session, show up as `ctx.agent.attributes[...]` in tools
  and hooks.
- **Calls.**
  - `ask` (blocking, for `def` endpoints) and `aask`.
  - `conversation(id).send` / `asend`, `resume` / `aresume`, `exists` and `delete`.
  - `@prompt` methods. They use `str.format` templates that can reference the method
    arguments plus `user`, `request`, `agent` and `conversation_id`. The return annotation
    (`str`, a Pydantic model, or `CopilotReply`) sets the type of the reply. Undefined
    variables fail when the class is defined, not when it is called.
- **Tools.** `@copilot_tool` wraps a function as a tool.
  - An `HTTPException` with a 4xx status becomes a tool error, and the model sees its
    `detail`. A 5xx error stays generic.
  - `requires=lambda ctx: ...` adds an application-level access guard.
  - A sync tool called from an `async def` endpoint runs in the threadpool. Context
    variables are propagated.
  - An async tool runs on the Copilot event loop, not the request's loop. Do not pass it
    objects that are bound to the request's loop, such as an async database session.
- **Conversations are partitioned per user.** The session ID includes
  `user_key(current_user)`. By default the key is the `id`, `pk`, `sub` or `username`
  attribute, or the value itself if it is a `str`/`int`. Users therefore cannot resume each
  other's conversations. If a user has no key, the call fails; sessions are never silently
  shared.
- **Safe defaults.**
  - Permissions default to `deny`.
  - Every session gets an isolated working directory under `<base_directory>/workspaces`.
    It is removed when a one-shot call ends or a conversation is deleted.
- **Startup validation.** The lifespan builds every agent and fails startup if there are
  unknown tools, MCP servers or providers, or invalid tool filters. It logs warnings for
  `allow_all`, a runtime that has not been downloaded, and many stdio workers. You can
  also call `copilot.check()` yourself, for example in CI.
- **Error mapping.**

  | Error | HTTP status |
  | --- | --- |
  | `CopilotConversationNotFound` | 404 |
  | `CopilotTimeout` | 504 |
  | `CopilotInvocationError` | 502 |
  | `CopilotConfigurationError` | 500 |

  The response bodies are generic, so runtime details never leak to clients.
- **Health.** `copilot.router()` adds `GET /copilot/health`. Add `?deep=true` to start the
  runtime and check authentication.
- **Per-user GitHub tokens.** Subclass `copilot_fastapi.auth.UserTokenResolver` and pass it
  as `Copilot(token_resolver=...)`.

## Settings

`CopilotSettings` is a [pydantic-settings](https://docs.pydantic.dev/latest/concepts/pydantic_settings/)
model. It reads `COPILOT_*` environment variables and `.env`, and uses `__` as the nesting
delimiter. You can also build it in code: `Copilot(CopilotSettings(...))`.

```bash
COPILOT_RUNTIME__CONNECTION=uri            # stdio | uri | tcp | inprocess
COPILOT_RUNTIME__URL=http://copilot:8080
COPILOT_RUNTIME__CONNECTION_TOKEN=...
COPILOT_RUNTIME__START=eager               # lazy (default) | eager
COPILOT_RUNTIME__REQUEST_TIMEOUT=300
COPILOT_AUTH__GITHUB_TOKEN=...
COPILOT_DEFAULTS__MODEL=gpt-5
COPILOT_DEFAULTS__PERMISSIONS=deny
COPILOT_MCP_SERVERS='{"docs": {"type": "http", "url": "https://example.com/mcp"}}'
COPILOT_AGENTS='{"support": {"model": "claude-sonnet-4.5", "timeout": 60}}'
COPILOT_HOOKS='["app.hooks.AuditHooks"]'
```

Unknown keys in agent overrides are rejected. Secrets are `SecretStr` values and never
appear in `repr` or in logs. To download the runtime at image build time, run
`python -m copilot download-runtime`.

## Testing

```python
import pytest
from fastapi.testclient import TestClient

from copilot_fastapi.testing import copilot_test_runtime
from app.main import app, copilot


@pytest.fixture
def fake_copilot():
    with copilot_test_runtime(copilot) as fake:
        yield fake


def test_status(fake_copilot):
    fake_copilot.agent("support").calls_tool("lookup_order", {"order_id": 1}).replies("Shipped")
    with TestClient(app) as client:
        assert client.get("/orders/1/status").json() == {"answer": "Shipped"}
    assert fake_copilot.tool_calls("support")[0].ok
```

The fake runtime needs no CLI and no network access. Scripted tool calls really run your
tools, permission policies and guards.

## Example

[`example/`](example/) is a small helpdesk API with a triage agent:

```bash
cd python/fastapi/example
../../.venv/bin/python -m pytest
../../.venv/bin/uvicorn helpdesk.main:app   # then: curl -XPOST -H 'X-User: ana' localhost:8000/tickets/1/triage
```
