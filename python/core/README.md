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

## Fresh-session loops (Ralph loop)

`run_loop` (and `arun_loop`) sends the same prompt repeatedly, each iteration in a new
ephemeral session (an `invoke` without `conversation_id`). State lives on disk (for example
`IMPLEMENTATION_PLAN.md` in the agent's working directory), and builds or tests are the
quality gate.

```python
import subprocess
from pathlib import Path
from copilot_agents import StopReason, run_loop

result = run_loop(
    builder,  # an AgentInvoker
    lambda n: Path("PROMPT_build.md").read_text(),  # re-read every iteration
    max_iterations=20,
    iteration_timeout=900,
    check=lambda prev: subprocess.run(["pytest", "-q"]).returncode == 0,
    stop_when=lambda it: "ALL TASKS DONE" in (it.output or ""),
    on_iteration=lambda it: print(it.number, it.duration),
)
assert result.stop_reason in (StopReason.STOP_CONDITION, StopReason.MAX_ITERATIONS)
```

The loop stops at the first of `stop_when` (after a successful iteration), `check` returning
false (only between iterations), a failed or timed-out iteration (unless
`continue_on_failure=True`), the `cancel` event being set, or `max_iterations`. A callable
`step(iteration, prompt)` can replace the invoker; it must open a fresh session itself.

Each iteration uses the agent's permission policy; nothing is approved implicitly. Run loops
against a disposable checkout inside a container or VM, observe tool use with the agent's
hooks, and schedule them from Celery, a Django management command, or any job runner.

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
