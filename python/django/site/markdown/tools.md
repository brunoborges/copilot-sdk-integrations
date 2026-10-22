---
title: Tools
---

# Tools

`@copilot_tool` turns a typed function into a tool that agents can call. Put tools in a
`copilot_tools.py` module of an installed app; they are registered by name when the app loads.

```python
from copilot_django import ToolContext, ToolError, copilot_tool
from .models import Order


@copilot_tool
def lookup_order(order_id: int, ctx: ToolContext) -> dict:
    """Look up one of the current user's orders."""
    order = Order.objects.get(pk=order_id, owner=ctx.user)
    return {"id": order.pk, "status": order.status}


@copilot_tool(permission_required="shop.refund_order", atomic=True)
def refund_order(order_id: int, ctx: ToolContext) -> str:
    """Refund an order and mark it refunded."""
    order = Order.objects.select_for_update().get(pk=order_id)
    if order.status == "refunded":
        raise ToolError("Order is already refunded.")
    order.status = "refunded"
    order.save()
    return "Refunded."


@copilot_tool
async def count_orders(ctx: ToolContext) -> int:
    """Count the current user's orders."""
    return await Order.objects.filter(owner=ctx.user).acount()
```

- The function's docstring becomes the tool description.
- Its type hints become the JSON schema. Pydantic models are supported as parameters.
- `ctx: ToolContext` is optional and isn't shown to the model. It gives the tool `user`,
  `request`, `conversation_id`, `session_id`, and the agent name.
- The return value is sent to the model: strings as-is, everything else as JSON.

Decorated functions stay ordinary callables, so they can be unit-tested directly.

## Options

| Option | Effect |
|---|---|
| `name`, `description` | Override the function name and docstring. |
| `permission_required` | One or more Django permissions. The calling user must have all of them (`user.has_perms`, or `ahas_perms` in async tools), otherwise the tool fails. |
| `atomic`, `using` | Run the tool in `transaction.atomic(using=...)`. Sync tools only. |
| `skip_permission` | Skip the agent's Copilot [permission policy](permissions.md) for this tool. Django permissions still apply. |
| `overrides_built_in_tool` | Let the tool replace a built-in runtime tool with the same name. |
| `register` | `False` keeps the tool out of the registry, so agents must reference the function object. |

## Errors

| Raised in the tool | The model sees |
|---|---|
| `ToolError("message")` | The message. Use this for expected failures. |
| `ObjectDoesNotExist` (for example `Order.DoesNotExist`) | `Not found.` |
| `PermissionDenied` | The exception message, or a generic "permission denied" message. |
| Any other exception | A generic failure. With `DEBUG = True`, the exception text. |

Unexpected exceptions are logged with their traceback.

## Threads and transactions

- **Sync views** (`ask`, `send`, sync `@prompt` methods): sync tools run on the request
  thread. They share the view's database connection and transaction, so they see rows the view
  created but hasn't committed yet. This includes `TestCase` transactions.
- **Async views** (`aask`, `asend`, async `@prompt` methods): sync tools run through
  `sync_to_async(thread_sensitive=True)`, which is Django's normal rule for ORM access from
  async code.
- **Async tools** run on the runtime's event loop and should use Django's async ORM API.

## Referencing tools from agents

```python
class SupportAgent(CopilotAgent):
    tools = [
        lookup_order,                               # the function
        "refund_order",                             # a registered name
        "billing.copilot_tools.issue_credit",       # a dotted path
    ]
```

An unknown name is reported as `copilot.E002`. Two different functions registered with the same
name are reported as `copilot.E005`.
