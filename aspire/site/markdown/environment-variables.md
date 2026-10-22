---
title: Environment variable reference
---

# Environment variable reference

This page lists every environment variable that a service receives when it references a Copilot
runtime or model, and the variables that the AppHost sets on the runtime itself. The values in the
examples come from an AppHost like this one:

```cs
var copilot = builder.AddCopilot("copilot")
                     .WithMcpServer(docs, tools: ["search_docs", "get_page"]);
var chat = copilot.AddModel("chat", "gpt-5.4").WithReasoningEffort("high");

builder.AddNodeApp("web", "../web", "server.js").WithReference(chat);
```

The dashboard shows the variables that each resource actually received under the resource's
details. Check there when in doubt.

## Connection string

| Variable | Value |
| --- | --- |
| `ConnectionStrings__{name}` | `Endpoint=tcp://{host}:{port};ConnectionToken={token}` for a runtime. A model appends `;Model={model}`, and a BYOK model then appends `;Provider=…;ProviderEndpoint=…;ProviderKey=…` and optionally `;WireApi=…`. |

Example:

```text
ConnectionStrings__chat=Endpoint=tcp://localhost:5000;ConnectionToken=s3cret;Model=gpt-5.4
```

The connection string is a list of `key=value` pairs separated by `;`. The .NET integrations parse
it for you. Other languages can split it, or use the connection properties below.

## Connection properties

Each connection property becomes a variable named `{NAME}_{PROPERTY}`. `{NAME}` is the resource name
in upper case, with hyphens, dots, and other characters that aren't ASCII letters, digits, or
underscores replaced by `_` (a resource named `copilot-public` gives `COPILOT_PUBLIC_HOST`). These
are Aspire's [resource property rules](https://aspire.dev/fundamentals/environment-variables/#resource-properties).

| Variable | From | Example | Description |
| --- | --- | --- | --- |
| `{NAME}_HOST` | Runtime and model | `localhost` | The runtime host. |
| `{NAME}_PORT` | Runtime and model | `5000` | The runtime port. |
| `{NAME}_ENDPOINT` | Runtime and model | `tcp://localhost:5000` | The runtime endpoint. The `tcp://` scheme is not accepted by the Node.js and Python SDKs or by the Spring Boot and Quarkus integrations; combine `{NAME}_HOST` and `{NAME}_PORT` for them. |
| `{NAME}_CONNECTIONTOKEN` | Runtime and model | `s3cret` | The connection token. A secret. |
| `{NAME}_MODEL` | Model only | `gpt-5.4` | The model identifier. |

For a model resource, `{NAME}` is the model's name (`CHAT_HOST`), not the runtime's. A service that
references the runtime directly gets `COPILOT_HOST`, `COPILOT_PORT`, and so on, but no model.

### Existing runtimes

When the runtime uses `AsExisting(endpoint, token)`, it has no `HOST`, `PORT`, `ENDPOINT`, or
`CONNECTIONTOKEN` properties. Services receive only the connection string, plus `{NAME}_MODEL` for a
model. Read the endpoint and token from `ConnectionStrings__{name}` and strip the `tcp://` scheme
before you pass the address to a non-.NET SDK.

## Copilot settings

These follow the .NET configuration convention: `__` separates configuration sections. The .NET
integration binds them to its `Copilot` section automatically. Other languages can read them as
plain environment variables.

| Variable | Set when | Received by | Description |
| --- | --- | --- | --- |
| `Copilot__McpServers__{server}__Type` | `WithMcpServer` | Services that reference the runtime or any of its models | Always `http`. |
| `Copilot__McpServers__{server}__Url` | `WithMcpServer` | Same | The server URL as the runtime reaches it, including the path (`/mcp` by default). |
| `Copilot__McpServers__{server}__Tools__{i}` | `WithMcpServer` with `tools` | Same | One variable per allowed tool, numbered from `0`. |
| `Copilot__ReasoningEffort` | `WithReasoningEffort` | Services that reference that model | `low`, `medium`, `high`, or `xhigh`. |

`{server}` is the MCP server's resource name, unchanged. Variable names are case-sensitive on Linux
and macOS.

Example:

```text
Copilot__McpServers__docs__Type=http
Copilot__McpServers__docs__Url=http://localhost:7000/mcp
Copilot__McpServers__docs__Tools__0=search_docs
Copilot__McpServers__docs__Tools__1=get_page
Copilot__ReasoningEffort=high
```

## What services don't receive

- The GitHub token. Only the runtime gets it.
- BYOK provider settings as separate variables. They are only in the model connection string.

## Variables on the runtime

The AppHost sets these on the runtime resource. Services don't receive them.

| Variable | Value |
| --- | --- |
| `COPILOT_CONNECTION_TOKEN` | The connection token parameter. |
| `COPILOT_GITHUB_TOKEN` | The GitHub token, when `WithGitHubToken` is used or the runtime runs as a container. |
| `COPILOT_HOME` | The data directory. See [Storage](runtime.md#storage). |
| `COPILOT_OTEL_ENABLED` | `true`, only when Aspire supplies an OTLP endpoint. |
| `OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT` | `false`, or `true` after `WithMessageContentCapture()`. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` and the other `OTEL_*` variables | Set by Aspire's OTLP exporter, using HTTP/protobuf. |

## Variables on the AppHost

| Variable | Description |
| --- | --- |
| `COPILOT_CLI_PATH` | The runtime executable to use in executable mode. Takes precedence over the bundled runtime and `PATH`. |

AppHost parameters such as `{name}-connection-token` and `{name}-github-token` take their values from
configuration (`Parameters:{parameter name}`), as with any Aspire parameter.
