---
title: Testing
---

# Testing

`copilot_fastapi.testing` replaces the runtime with a scripted in-memory fake. Tests don't need
the Copilot CLI, a token, or network access. Scripted tool calls **really run your tools**,
permission policies and guards, with the same user and dependencies as in production.

## pytest

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

    [call] = fake_copilot.tool_calls("support")
    assert call.name == "lookup_order"
    assert call.ok
```

`copilot.test_runtime()` is the same context manager as a method. Use `with TestClient(app)` so
the lifespan runs and startup validation is part of the test. Override `current_user` with
`app.dependency_overrides` as usual.

## Scripting replies

Each `agent(name)` script is a queue of steps, consumed one turn at a time:

| Step | Effect |
|---|---|
| `.replies("text", ...)` | Replies with text, one turn per argument. |
| `.replies_json(value)` | Replies with `value` serialized as JSON, for typed `@prompt` methods. |
| `.calls_tool(name, arguments)` | Calls a tool, subject to tool filters and the permission policy, then continues with the next step in the same turn. |
| `.fails("message")` | The turn fails with a runtime error. |

`copilot_test_runtime(copilot, default_reply="...")` replies to unscripted turns. Without it, an
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

## Validating configuration

```python
def test_copilot_configuration():
    warnings = copilot.check()          # raises CopilotConfigurationError on errors
    assert not any("allow_all" in w for w in warnings)
```
