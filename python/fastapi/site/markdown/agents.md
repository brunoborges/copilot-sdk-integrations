---
title: Agents and dependencies
---

# Agents and dependencies

An agent is a `CopilotAgent` subclass. Register it with a `Copilot` instance, either with
`Copilot(agents=[...])`, `@copilot.agent`, or implicitly the first time you call
`copilot.depends(...)` or `copilot.get(...)`.

```python
from copilot_fastapi import CopilotAgent, policies


class SupportAgent(CopilotAgent):
    name = "support"                     # default: "support" (class name minus "Agent", snake_case)
    instructions = "You are Contoso's support agent."
    model = "gpt-5"
    tools = [lookup_order, "app.tools.refund_order"]
    mcp_servers = ["docs"]               # names from settings.mcp_servers
    built_in_tools = False
    permissions = policies.allow_registered_tools()
```

Subclasses that set `abstract = True` can't be instantiated, so a project can share a base
class. Agent names must be unique within a `Copilot` instance.

## Attributes

| Attribute | Purpose |
|---|---|
| `name` | Registry name, used in settings overrides, session IDs, and test scripts. |
| `instructions` | System instructions as plain text. |
| `instructions_mode` | `"append"` (default) adds to the runtime's system message; `"replace"` replaces it. |
| `system_message` | A raw SDK system-message config, for full control. |
| `model`, `reasoning_effort` | Model selection. Default: `settings.defaults`. |
| `provider` | A name from `settings.providers` or a BYOK provider mapping. |
| `tools` | [Tools](tools.md): functions or dotted paths. |
| `mcp_servers` | Names from `settings.mcp_servers`, or a mapping of server configs. |
| `available_tools`, `excluded_tools` | Runtime tool filters such as `builtin:view`, `custom:*`, `mcp:docs/*`. |
| `built_in_tools` | `False` excludes the runtime's built-in tools (shell, file edits, ...). |
| `excluded_built_in_agents` | Built-in sub-agents to disable. |
| `tool_search` | `{"enabled": bool, "defer_threshold": int}` (either key optional): override the runtime's tool search, which defers MCP and external tools behind `tool_search_tool` once the tool count reaches the threshold (runtime default: enabled, 30). Default: `None`, the runtime default. |
| `working_directory` | `"isolated"` (default), a path, or a callable `(ctx) -> path`. |
| `additional_directories` | Extra directories the agent may read. |
| `permissions` | A [permission policy](permissions.md). Default: deny everything. |
| `hooks` | `CopilotAgentHooks` instances, classes, or dotted paths. `Copilot(hooks=...)` and `settings.hooks` apply to every agent. |
| `token_resolver` | A [token resolver](conversations.md#per-user-tokens) for this agent. |
| `timeout` | Seconds per call. Default: `settings.runtime.request_timeout`. |

## Injecting agents

`copilot.depends(Agent, **deps)` returns a FastAPI `Depends` that builds a per-request agent
bound to the `Request` and to the user returned by your `current_user` dependency:

```python
copilot = Copilot(agents=[SupportAgent], current_user=get_current_user)

Support = Annotated[SupportAgent, copilot.depends(SupportAgent, db=Depends(get_db))]


@app.post("/support")
async def support(question: str, agent: Support) -> dict:
    return {"answer": await agent.aask(question)}
```

Extra keyword arguments are ordinary dependencies. Their values become `agent.attributes`, so
tools and hooks reach them through `ctx.agent.attributes["db"]`. `request` and `user` are
reserved names.

Outside a request (scripts, background tasks, workers), use `copilot.get`:

```python
agent = copilot.get(SupportAgent, user=user, attributes={"db": session})
```

## Calling an agent

```python
text = await agent.aask("What can you help with?")             # one-shot, ephemeral session
decision = await agent.aask("Refund order 7?", result=Decision) # parsed into a pydantic model
reply = await agent.aask("Hi", result=CopilotReply)            # text, session_id, tool_calls

text = agent.ask("What can you help with?")                     # blocking, for def endpoints
```

One-shot calls use an ephemeral session that is deleted afterwards. For multi-turn chat use
[conversations](conversations.md). For reusable prompts use [`@prompt` methods](prompts.md).

Sync methods (`ask`, `send`, ...) raise an error when called inside a running event loop. Use
them from `def` endpoints, which FastAPI runs in its threadpool, and the `a`-prefixed methods
from `async def` endpoints.

## Errors

| Exception | When | HTTP status |
|---|---|---|
| `CopilotConfigurationError` | The agent definition is invalid (unknown tool, MCP server, provider, ...). | 500 |
| `CopilotConversationNotFound` | `resume()` on a conversation that does not exist. | 404 |
| `CopilotTimeout` | The call exceeded its timeout; the turn is aborted. | 504 |
| `CopilotInvocationError` | The runtime reported an error, or a typed result could not be parsed. | 502 |

All of them derive from `CopilotAgentError`. The status codes apply when the
[exception handlers](operations.md#error-mapping) are installed.

## Working directories

By default every session gets its own directory under
`<settings.runtime.base_directory>/workspaces`. It is removed when an ephemeral session ends or a
conversation is deleted, so files written by one user's agent are never visible to another.
