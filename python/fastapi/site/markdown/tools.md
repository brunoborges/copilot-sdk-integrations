---
title: Tools
---

# Tools

`@copilot_tool` turns a typed function into a tool that agents can call.

```python
from fastapi import HTTPException
from copilot_fastapi import ToolContext, ToolError, copilot_tool


@copilot_tool
def lookup_order(order_id: int, ctx: ToolContext) -> dict:
    """Look up one of the current user's orders."""
    db = ctx.agent.attributes["db"]
    order = db.get_order(order_id, owner=ctx.user.id)
    if order is None:
        raise HTTPException(404, "Order not found")
    return {"id": order.id, "status": order.status}


@copilot_tool(requires=lambda ctx: ctx.user.is_admin)
def refund_order(order_id: int, ctx: ToolContext) -> str:
    """Refund an order."""
    order = ctx.agent.attributes["db"].get_order(order_id)
    if order.status == "refunded":
        raise ToolError("Order is already refunded.")
    order.status = "refunded"
    return "Refunded."
```

- The function's docstring becomes the tool description.
- Its type hints become the JSON schema. Pydantic models are supported as parameters.
- `ctx: ToolContext` is optional and isn't shown to the model. It gives the tool `user`,
  `request`, `agent_name`, `conversation_id`, `session_id`, and `agent.attributes` (the
  dependencies passed to `copilot.depends`).
- The return value is sent to the model: strings as-is, everything else as JSON.

Decorated functions stay ordinary callables, so they can be unit-tested directly.

## Options

| Option | Effect |
|---|---|
| `name`, `description` | Override the function name and docstring. |
| `requires` | An access guard `(ctx) -> bool`. A falsy result fails the tool with "permission denied". An async guard requires an async tool. |
| `skip_permission` | Skip the agent's Copilot [permission policy](permissions.md) for this tool. `requires` still applies. |
| `overrides_built_in_tool` | Let the tool replace a built-in runtime tool with the same name. |

## Errors

| Raised in the tool | The model sees |
|---|---|
| `ToolError("message")` | The message. Use this for expected failures. |
| `HTTPException` with a 4xx status | Its `detail`, so existing service functions can be reused unchanged. |
| `HTTPException` with a 5xx status | A generic failure. |
| Any other exception | A generic failure, unless `Copilot(expose_tool_errors=True)`. |

Unexpected exceptions are logged with their traceback.

## Threads and event loops

- **Sync tools** called from `def` endpoints run on the request's thread. Called from `async def`
  endpoints, they run in FastAPI's threadpool (`run_in_threadpool`). Context variables are
  propagated in both cases.
- **Async tools** run on the Copilot runtime's event loop, not the request's loop. Don't pass
  them objects bound to the request's loop, such as an async SQLAlchemy session. Use a sync tool
  with a sync session, or open a new async session inside the tool.

## Referencing tools from agents

```python
class SupportAgent(CopilotAgent):
    tools = [
        lookup_order,                       # the function
        "app.billing.issue_credit",         # a dotted path
    ]
```

An unknown dotted path fails startup validation.
