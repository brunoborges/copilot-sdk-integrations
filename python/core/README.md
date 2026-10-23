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
| `SessionStore` | Lists, reads (`get_messages`, without resuming), checks and deletes sessions; `session_id_for(agent, conversation_id, user=...)` maps a conversation to its session ID. Sync and `a`-prefixed async methods. |
| `SessionCleanup` | Opt-in sweeper that deletes this library's sessions (`agent-` prefix, not remote) older than `max_age`; `sweep()` / `asweep()` for one pass, `run()` for a loop. |
| `ErrorRecoveryHooks` | Opt-in hook that classifies failed tool results, nudges the model to keep going, and retries transient SDK errors. See [Error recovery](#error-recovery). |
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

## Listing, deleting and cleaning up sessions

```python
from datetime import timedelta
from copilot_agents import SessionCleanup, SessionStore

store = SessionStore(runtime)
for meta in store.list():                     # local sessions, newest first
    print(meta.session_id, meta.modified_time, meta.summary)

session_id = store.session_id_for(agent, "ticket-7", user="user-1")
store.exists(session_id)
for message in store.get_messages(session_id) or []:  # None when the session is missing
    print(message.role, message.content)
store.delete(session_id)                      # False when it did not exist

cleanup = SessionCleanup(store, max_age=timedelta(days=30))  # interval: max_age/2, 1 min..1 h
deleted = cleanup.sweep()                     # or: asyncio.create_task(cleanup.run())
```

- `get_messages` pages through the SDK's persisted events
  (`sessions.readPersistedEvents`), so the session is not resumed. It returns user and
  assistant messages in order, and skips sub-agent events, tool calls and empty assistant
  messages.
- `delete` and the sweep do not race a turn in flight: `delete` waits for running turns on
  that session, and new turns wait for the delete to finish. The sweep skips busy sessions.
- The sweep only deletes sessions that are not remote, whose ID starts with `agent-`, and
  that were last modified before `now - max_age`. It re-reads the metadata right before
  deleting. It never starts the runtime: if the runtime is not started yet, the sweep does
  nothing. Errors are logged; a failed delete does not stop the sweep.

## Behavior worth knowing

- **Sessions are opened per call.** Each `invoke` creates or resumes the session, sends one
  prompt, waits for the reply, and disconnects. Without a `conversation_id` the session is
  ephemeral and deleted afterwards.
- **Sync tools run on the caller's thread** when called through `invoke`, so they see the
  caller's transaction, thread-locals and context variables. Through `ainvoke` they run via
  the configured `sync_runner` (default: `run_in_executor`).
- **Permissions default to deny.** Without a policy, every permission request is rejected.
- **Message source.** `AgentDefinition(message_source="system")` (or `"user"`, `"agent-<id>"`)
  labels prompts; a `message_source(...)` scope overrides it, and calls made from another agent's
  tool are labeled `agent-<calling agent>` (an outer scope does not flow into tools). The label is
  caller-supplied provenance, not an authenticated identity: never authorize from it.
- **Session IDs** are `agent-<sha256>` over the agent type, name, conversation and partition,
  matching `java-core`.

## Development

```bash
cd python
uv venv && uv pip install -e core -e django pytest pytest-asyncio pytest-django
cd core && ../.venv/bin/python -m pytest
```

## Error recovery

`ErrorRecoveryHooks` is a ready-made `CopilotAgentHooks`, based on the Copilot SDK cookbook's
[error-recovery recipe](https://github.com/github/awesome-copilot/blob/main/cookbook/copilot-sdk/python/error-recovery-hooks.md).
It is off unless you add it:

```python
from copilot_agents import AgentDefinition, ErrorRecoveryHooks

AgentDefinition(name="ops", hooks=[ErrorRecoveryHooks(max_consecutive_failures=3, retry_count=2)])
```

- `on_post_tool_use` and `on_post_tool_use_failure` classify the tool output as permission denied,
  shell error or generic tool failure by case-insensitive phrase lists (`permission_phrases`,
  `shell_error_phrases`). On a failure they add a `[SYSTEM NOTE: ...]` as `additionalContext`
  telling the model not to stop. The tool result itself is not changed.
- After `max_consecutive_failures` (default 3, `0` = unlimited) failures of the same tool in a
  session, the note stops, so the model is not nudged into a loop. A successful call resets the count.
- `on_error_occurred` retries transient SDK errors (timeouts, 429, 5xx, connection resets, or any
  error the SDK marks `recoverable`) with `retry_count` attempts. Other errors are handled per
  `unrecoverable_handling` (`"skip"` by default, `"abort"`, or `None` to leave the SDK default).
- Hook chains use the first non-`None` result, so list `ErrorRecoveryHooks` after hooks that
  should win.

