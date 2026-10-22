---
title: Agents
---

# Agents

An agent is a `CopilotAgent` subclass. Put agents in a `copilot_agents.py` module of an
installed app; `copilot_django` imports these modules (and `copilot_tools.py`) at startup.

```python
from copilot_django import CopilotAgent, policies


class SupportAgent(CopilotAgent):
    name = "support"                     # default: "support" (class name minus "Agent", snake_case)
    instructions = "You are Contoso's support agent."
    model = "gpt-5"
    tools = [lookup_order, "refund_order", "billing.copilot_tools.issue_credit"]
    mcp_servers = ["docs"]               # names from COPILOT["MCP_SERVERS"]
    built_in_tools = False
    permissions = policies.allow_registered_tools()
```

Subclasses that set `abstract = True` are not registered, so a project can share a base class.
Two agents with the same name are reported as `copilot.E005`.

## Attributes

| Attribute | Purpose |
|---|---|
| `name` | Registry name, used in settings overrides, session IDs, and test scripts. |
| `instructions` | System instructions as plain text. |
| `instructions_template` | A Django template name rendered once with `agent` and `settings` when `instructions` is unset. |
| `instructions_mode` | `"append"` (default) adds to the runtime's system message; `"replace"` replaces it. |
| `system_message` | A raw SDK system-message config, for full control. |
| `model`, `reasoning_effort` | Model selection. Default: `COPILOT["DEFAULTS"]`. |
| `provider` | A name from `COPILOT["PROVIDERS"]` or a BYOK provider mapping. |
| `tools` | [Tools](tools.md): functions, registry names, or dotted paths. |
| `mcp_servers` | Names from `COPILOT["MCP_SERVERS"]`, or a mapping of server configs. |
| `available_tools`, `excluded_tools` | Runtime tool filters such as `builtin:view`, `custom:*`, `mcp:docs/*`. |
| `built_in_tools` | `False` excludes the runtime's built-in tools (shell, file edits, ...). |
| `excluded_built_in_agents` | Built-in sub-agents to disable. |
| `working_directory` | `"isolated"` (default), a path, or a callable `(ctx) -> path`. |
| `additional_directories` | Extra directories the agent may read. |
| `permissions` | A [permission policy](permissions.md). Default: deny everything. |
| `hooks` | `CopilotAgentHooks` instances, classes, or dotted paths. Global `COPILOT["HOOKS"]` run first. |
| `token_resolver` | A [token resolver](conversations.md#per-user-tokens) for this agent. |
| `timeout` | Seconds per call. Default: `RUNTIME["REQUEST_TIMEOUT"]`. |

Any attribute can be overridden per environment in `COPILOT["AGENTS"][name]` with upper-case
keys (for example `"MODEL"`, `"TOOLS"`, `"PERMISSIONS"`).

## Calling an agent

```python
agent = SupportAgent()                      # user and request come from the middleware
agent = SupportAgent(user=some_user)        # or pass them explicitly (tasks, commands)

text = agent.ask("What can you help with?")             # one-shot, ephemeral session
decision = agent.ask("Refund order 7?", result=Decision) # parsed into a pydantic model
reply = agent.ask("Hi", result=CopilotReply)            # text, session_id, tool_calls

text = await agent.aask("What can you help with?")
```

One-shot calls use an ephemeral session that is deleted afterwards. For multi-turn chat use
[conversations](conversations.md). For reusable prompts use [`@prompt` methods](prompts.md).

Sync methods (`ask`, `send`, ...) raise an error when called inside a running event loop; use
the `a`-prefixed methods there.

## Errors

| Exception | When |
|---|---|
| `CopilotConfigurationError` | The agent definition is invalid (unknown tool, MCP server, provider, ...). |
| `CopilotConversationNotFound` | `resume()` on a conversation that does not exist. |
| `CopilotTimeout` | The call exceeded its timeout; the turn is aborted. |
| `CopilotInvocationError` | The runtime reported an error, or a typed result could not be parsed. |

All of them derive from `CopilotAgentError`.

## Working directories

By default every session gets its own directory under
`COPILOT["RUNTIME"]["BASE_DIRECTORY"] / "workspaces"`. It is removed when an ephemeral session
ends or a conversation is deleted, so files written by one user's agent are never visible to
another.
