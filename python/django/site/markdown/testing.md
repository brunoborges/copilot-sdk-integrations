---
title: Testing
---

# Testing

`copilot_django.test` replaces the runtime with a scripted in-memory fake. Tests don't need the
Copilot CLI, a token, or network access. Scripted tool calls **really run your tools** against
the test database, with the same user, permission and transaction handling as in production.

## Django test cases

```python
from django.test import TestCase
from copilot_django.test import CopilotTestMixin


class RefundTests(CopilotTestMixin, TestCase):
    def test_refund_decision(self):
        order = Order.objects.create(status="late", owner=self.user)
        self.copilot.agent("support") \
            .calls_tool("lookup_order", {"order_id": order.pk}) \
            .replies_json({"approve": True, "reason": "Order is late."})

        self.client.force_login(self.user)
        response = self.client.post(f"/orders/{order.pk}/refund/")

        self.assertEqual(response.status_code, 200)
        [call] = self.copilot.tool_calls("support")
        self.assertEqual(call.name, "lookup_order")
        self.assertTrue(call.ok)
```

`self.copilot` is a fresh `CopilotTestRuntime` for every test.

## pytest

Installing `copilot-django` registers a pytest plugin with a `copilot_runtime` fixture:

```python
@pytest.mark.django_db
def test_greeting(copilot_runtime, rf, django_user_model):
    copilot_runtime.agent("support").replies("Hello!")
    user = django_user_model.objects.create(username="ana")

    assert SupportAgent(user=user).ask("Hi") == "Hello!"
    assert copilot_runtime.prompts("support") == ["Hi"]
```

You can also use `with copilot_test_runtime() as fake:` anywhere.

## Scripting replies

Each `agent(name)` script is a queue of steps, consumed one turn at a time:

| Step | Effect |
|---|---|
| `.replies("text", ...)` | Replies with text, one turn per argument. |
| `.replies_json(value)` | Replies with `value` serialized as JSON, for typed `@prompt` methods. |
| `.calls_tool(name, arguments)` | Calls a tool, subject to tool filters and the permission policy, then continues with the next step in the same turn. |
| `.fails("message")` | The turn fails with a runtime error. |

`CopilotTestRuntime(default_reply="...")` replies to unscripted turns. Without it, an
unscripted turn is a test failure.

## Assertions

| Method | Returns |
|---|---|
| `prompts(agent)` | Prompts the agent was sent, in order. |
| `tool_calls(agent=None)` | `RecordedToolCall`s with `name`, `arguments`, `approved`, `ok`, and `text`. |
| `sessions(agent=None)` | IDs of sessions that still exist. Ephemeral sessions are deleted after each call. |
| `last_session_options(agent)` | The SDK options of the agent's most recent session: model, tools, MCP servers, filters, ... |
| `created`, `resumed`, `deleted`, `aborted` | Session IDs, in order. |

A denied tool call is recorded with `approved=False`, which makes it easy to test permission
policies.
