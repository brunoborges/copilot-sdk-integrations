---
title: Permissions
---

# Permissions

The Copilot runtime asks for permission before an agent runs a tool, calls an MCP server,
reads or writes files, or runs shell commands. In `copilot-fastapi` every request is **denied
unless the agent's policy approves it**.

```python
from copilot_fastapi import CopilotAgent, policies


class SupportAgent(CopilotAgent):
    tools = [lookup_order, refund_order]
    mcp_servers = ["docs"]
    permissions = policies.any_of(
        policies.allow_registered_tools(),        # this agent's own tools
        policies.allow_mcp_servers("docs"),
        policies.allow_read_only(),               # file reads and read-only MCP tools
    )
```

## Built-in policies

| Policy | Approves |
|---|---|
| `deny_all()` | Nothing. This is the default. |
| `allow_registered_tools()` | The custom tools listed in the agent's `tools`. |
| `allow_tools("name", "server/tool")` | Named custom tools and MCP tools. |
| `allow_mcp_servers("docs")` | Every tool on the named MCP servers. |
| `allow_read_only()` | File reads and MCP tools marked read-only. |
| `allow_kinds("read", "url")` | Requests of the given runtime kinds. |
| `allow_all()` | Everything. Logged as a warning at startup. |
| `any_of(a, b, ...)` | The first policy that approves wins. |

## Custom policies

A policy can be any callable `(request, invocation)`. Return `True` or an SDK decision to
approve; return `False` or `None` to deny.

```python
def small_refunds_only(request, invocation):
    if request.kind == "custom-tool" and request.tool_name == "refund_order":
        return (request.args or {}).get("amount", 0) < 100
    return None


class SupportAgent(CopilotAgent):
    permissions = policies.any_of(policies.allow_tools("lookup_order"), small_refunds_only)
```

## In settings

`COPILOT_DEFAULTS__PERMISSIONS` and per-agent `permissions` overrides accept:

- a policy name: `deny`, `allow_all`, `allow_read_only`, `allow_registered_tools`;
- a dotted path to a policy or callable;
- a mapping such as `{"allow_tools": ["lookup_order"], "allow_mcp_servers": ["docs"]}`
  (keys, case-insensitive: `allow_all`, `allow_kinds`, `allow_tools`, `allow_mcp_servers`,
  `allow_read_only`, `allow_registered_tools`).

```sh
COPILOT_AGENTS='{"support": {"permissions": {"allow_tools": ["lookup_order"]}}}'
```

## Two layers

Copilot permissions decide whether the **agent** may use a tool. The tool's `requires=` guard
(see [Tools](tools.md#options)) decides whether the **user** the agent acts for may use it. Both
must pass.

Interactive approvals, where a person approves each request, are planned for a later
milestone.
