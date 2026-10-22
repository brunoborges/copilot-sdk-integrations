# copilot-agent-core

Framework-neutral agent layer for the [GitHub Copilot SDK for Python](https://pypi.org/project/github-copilot-sdk/).
It is the Python counterpart of [`java-core`](../../java-core/): framework integrations such as
[`copilot-django`](../django/) build on it, and it can also be used directly.

## What it provides

| Module | Purpose |
|---|---|
| `AgentDefinition` | Immutable description of an agent: instructions, model, tools, MCP servers, tool filters, permissions, hooks, working directory, token resolver. `validate()` returns categorized `ConfigIssue`s. |
| `AgentInvoker` | Runs prompts against a definition: `invoke` / `ainvoke`, `exists` / `aexists`, `delete` / `adelete`, typed (`pydantic`) results. |
| `CopilotRuntime` | Owns one `CopilotClient` on a dedicated event-loop thread; usable from sync and async code, fork-safe, reusable after `shutdown()`. |
| `tool` / `ToolContext` / `ToolError` | Turns a typed function into a Copilot tool. `ctx: ToolContext` exposes the user, request and conversation. |
| `policies` | Permission policies: `deny_all`, `allow_all`, `allow_kinds`, `allow_tools`, `allow_mcp_servers`, `allow_read_only`, `allow_registered_tools`, `any_of`. |
| `derive_session_id` | Deterministic session IDs, byte-compatible with the Java integrations. |
| `testing.CopilotTestRuntime` | In-memory runtime that scripts replies and tool calls, no CLI or network needed. |

## Example

```python
from pydantic import BaseModel
from copilot_agents import AgentDefinition, AgentInvoker, CopilotRuntime, ToolContext, policies, tool
from copilot import CopilotClient


@tool
def lookup_order(order_id: int, ctx: ToolContext) -> str:
    """Look up an order."""
    return f"order {order_id}: shipped"


class Answer(BaseModel):
    status: str


runtime = CopilotRuntime(lambda: CopilotClient())
agent = AgentInvoker(
    AgentDefinition(
        name="support",
        instructions="You answer order questions.",
        tools=[lookup_order],
        permissions=policies.allow_registered_tools(),
    ),
    runtime,
)

print(agent.invoke("Where is order 42?", result=Answer))
print(agent.invoke("And order 43?", conversation_id="ticket-7", user="user-1"))
```

## Behavior worth knowing

- **Sessions are opened per call.** Each `invoke` creates or resumes the session, sends one
  prompt, waits for the reply, and disconnects. Without a `conversation_id` the session is
  ephemeral and deleted afterwards.
- **Sync tools run on the caller's thread** when called through `invoke`, so they see the
  caller's transaction, thread-locals and context variables. Through `ainvoke` they run via
  the configured `sync_runner` (default: `run_in_executor`).
- **Permissions default to deny.** Without a policy, every permission request is rejected.
- **Session IDs** are `agent-<sha256>` over the agent type, name, conversation and partition,
  matching `java-core`.

## Development

```bash
cd python
uv venv && uv pip install -e core -e django pytest pytest-asyncio pytest-django
cd core && ../.venv/bin/python -m pytest
```
