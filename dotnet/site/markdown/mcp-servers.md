---
title: MCP servers
---

# MCP servers

Agents can call tools from [Model Context Protocol](https://modelcontextprotocol.io/) servers.
Servers are declared in configuration (or code) under `McpServers`, keyed by server name, and
mirror the SDK's HTTP and stdio server configs. Only the tools you list are exposed and allowed.

**In this chapter**

- [HTTP servers](#http-servers)
- [Stdio servers](#stdio-servers)
- [Server options](#server-options)
- [Tool allow-lists](#tool-allow-lists)
- [Named agents](#named-agents)
- [Validation](#validation)

## HTTP servers

```json
{
  "Copilot": {
    "McpServers": {
      "docs": {
        "Type": "http",
        "Url": "https://docs.internal/mcp",
        "Headers": { "Authorization": "Bearer <from a secret store>" },
        "Tools": [ "search" ]
      }
    }
  }
}
```

## Stdio servers

```json
{
  "Copilot": {
    "McpServers": {
      "files": {
        "Command": "npx",
        "Args": [ "-y", "@modelcontextprotocol/server-filesystem", "/srv/shared" ],
        "Tools": [ "read_file", "list_directory" ],
        "Timeout": 30000
      }
    }
  }
}
```

The same servers can be added in code:

```cs
builder.Services.AddCopilot(o => o.McpServers["github"] = new CopilotMcpServerOptions
{
    Url = "https://example.com/mcp",
    Tools = ["list_issues"],
});
```

## Server options

`CopilotMcpServerOptions` is a bindable mirror of the SDK's `McpHttpServerConfig` and
`McpStdioServerConfig`:

| Option | Applies to | Description |
| --- | --- | --- |
| `Type` | both | `http` (also `sse`) or `stdio` (also `local`), case-insensitive. When unset, `http` if `Url` is set, `stdio` if `Command` is set. |
| `Tools` | both | Tools the agent may call on this server. An empty list exposes none. `"*"` allows all. |
| `Timeout` | both | Optional timeout for tool calls to this server, in milliseconds. |
| `Url` | http | The server URL. |
| `Headers` | http | HTTP headers sent to the server. |
| `Command` | stdio | The command that starts the server. |
| `Args` | stdio | Arguments for `Command`. |
| `Env` | stdio | Environment variables for the server. |
| `WorkingDirectory` | stdio | Working directory for the server. |

## Tool allow-lists

MCP tools are deny-by-default, like everything else the
[default permission handler](permissions.md#the-default-handler) doesn't recognize:

- `Tools` is passed to the runtime, so only the listed tools are exposed.
- When any server lists tools, the session's `AvailableTools` includes `mcp:*`.
- A call is approved only if its server is configured on the agent and the tool is listed
  (by its name, or as `{server}-{name}`), or the list contains `"*"`.
- An empty `Tools` list allows none of the server's tools.

## Named agents

Named agents inherit the default agent's `McpServers` and can add or override entries in code
or under `Copilot:Agents:{name}:McpServers`. See [Named agents](chat-clients.md#named-agents).

With Aspire, MCP servers declared in the AppHost reach your service as `Copilot__McpServers__*`
settings; see the
[Copilot Agents for Aspire](https://brunoborges.github.io/copilot-sdk-integrations/aspire/)
documentation.

## Validation

At startup, an HTTP server needs an absolute `http` or `https` `Url`, a stdio server needs a
`Command`, a server with neither is rejected, unknown `Type` values are rejected, and `Timeout`
must be positive when set. Messages name the key, for example
`Copilot:McpServers:docs:Url must be an absolute http(s) URL for an http server.`
