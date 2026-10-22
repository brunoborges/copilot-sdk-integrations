---
title: Permissions
---

# Permissions

The Copilot runtime asks for permission before an agent runs a tool, calls an MCP server,
reads or writes files, or runs shell commands. In `copilot-django` every request is **denied
unless the agent's policy approves it**.

```python
from copilot_django import CopilotAgent, policies


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
| `allow_all()` | Everything. Reported as `copilot.W002`. |
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

`COPILOT["DEFAULTS"]["PERMISSIONS"]` and per-agent `"PERMISSIONS"` overrides accept:

- a policy name: `"deny"`, `"allow_all"`, `"allow_read_only"`, `"allow_registered_tools"`;
- a dotted path to a policy or callable;
- a mapping such as `{"ALLOW_TOOLS": ["lookup_order"], "ALLOW_MCP_SERVERS": ["docs"]}`
  (keys: `ALLOW_ALL`, `ALLOW_KINDS`, `ALLOW_TOOLS`, `ALLOW_MCP_SERVERS`, `ALLOW_READ_ONLY`,
  `ALLOW_REGISTERED_TOOLS`).

## Two layers

Copilot permissions decide whether the **agent** may use a tool. Django permissions
(`@copilot_tool(permission_required=...)`) decide whether the **user** the agent acts for may
use it. Both must pass.

Interactive approvals, where a person approves each request, are planned for a later
milestone.
