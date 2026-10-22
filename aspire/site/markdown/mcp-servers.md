---
title: MCP servers
---

# MCP servers

`WithMcpServer` connects an MCP server that runs in the AppHost to the Copilot runtime. The server
can be written in any language: it only needs an HTTP endpoint that speaks the MCP streamable HTTP
transport.

```cs
var docs = builder.AddProject<Projects.DocsMcp>("docs");   // or AddNodeApp, AddPythonApp, AddContainer, ...

var copilot = builder.AddCopilot("copilot")
                     .WithMcpServer(docs, tools: ["search_docs", "get_page"]);
var chat = copilot.AddModel("chat", "gpt-5.4");

builder.AddProject<Projects.Api>("api").WithReference(chat);
```

| Parameter | Default | Description |
| --- | --- | --- |
| `server` | Required | A resource with endpoints: a project, executable, or container. |
| `path` | `/mcp` | The MCP path on the server. A leading `/` is added if missing. |
| `tools` | None | The tools the agent may call. |
| `endpointName` | `http`, then `https`, then the first endpoint | The server endpoint to use. |

`WithMcpServer` throws if the server has no endpoints. The runtime waits for the server to start,
unless the runtime is an [existing runtime](runtime.md#existing-runtime).

## What services receive

The MCP server is configured on the runtime, but the Copilot SDKs attach MCP servers per session.
So the AppHost passes the server settings to every service that references the runtime or one of
its models:

```text
Copilot__McpServers__docs__Type=http
Copilot__McpServers__docs__Url=http://localhost:7000/mcp
Copilot__McpServers__docs__Tools__0=search_docs
Copilot__McpServers__docs__Tools__1=get_page
```

The server name in the variable is the resource name, unchanged (`docs-mcp` stays `docs-mcp`). The
URL is the address at which **the runtime** reaches the server, not the service: `localhost` when
the runtime is an executable, or the container network address when it runs as a container. The
runtime, not the service, calls the MCP server.

Resources that don't reference a runtime or model don't receive these variables, and neither does
the runtime itself.

## Tools

The tool list is an allow-list. The .NET integration exposes only listed tools, so with no
`tools`, a .NET service must configure `Copilot:McpServers:{server}:Tools` itself. Other
integrations have the same rule; for example, the Spring Boot and Quarkus integrations default to
no tools, and `*` enables all of them.

## Using the settings in each language

| Language | How the settings are applied |
| --- | --- |
| .NET | Automatically. The core integration binds `Copilot:McpServers` from configuration. |
| Spring Boot | Map the variables to `copilot.mcp.servers.<server>.*` and select the server per agent. |
| Quarkus | Map the variables to `quarkus.copilot.mcp.servers.<server>.*` and select the server per agent. |
| Node.js | Read the variables and pass `mcpServers` to `defineAgent`. |
| Python | Read the variables and pass `mcp_servers` to `create_session`. |

For example, in Spring Boot:

```properties
copilot.mcp.servers.docs.type=http
copilot.mcp.servers.docs.url=${Copilot__McpServers__docs__Url}
copilot.mcp.servers.docs.tools=search_docs,get_page
copilot.agents.support.mcp-servers=docs
```

In Node.js:

```ts
const support = defineAgent({
  name: "support",
  instructions: "Answer questions from the product docs.",
  model: process.env.CHAT_MODEL,
  mcpServers: {
    docs: {
      type: "http",
      url: process.env.Copilot__McpServers__docs__Url!,
      tools: ["search_docs", "get_page"],
    },
  },
});
```

In Python:

```python
session = await client.create_session(
    model=os.environ["CHAT_MODEL"],
    mcp_servers={
        "docs": {
            "type": "http",
            "url": os.environ["Copilot__McpServers__docs__Url"],
            "tools": ["search_docs", "get_page"],
        },
    },
    on_permission_request=PermissionHandler.approve_all,
)
```

Environment variable names are case-sensitive on Linux and macOS; use the exact names shown on the
dashboard.
