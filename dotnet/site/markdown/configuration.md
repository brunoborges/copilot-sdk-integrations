---
title: Configuration
---

# Configuration

Every agent is configured through `CopilotOptions`. Values come from code (the `AddCopilot`
delegate), the `Copilot` configuration section, and a `ConnectionStrings:{name}` entry.
Configuration overrides code, so operators can change a model or a timeout without a rebuild.

**In this chapter**

- [The Copilot section](#the-copilot-section)
- [Options reference](#options-reference)
- [Binding order](#binding-order)
- [Connection strings](#connection-strings)
- [BYOK providers](#byok-providers)
- [Validation](#validation)
- [Escape hatches](#escape-hatches)

## The Copilot section

```jsonc
{
  "Copilot": {
    "Model": "gpt-5.4",
    "Instructions": "You help customers with their orders.",
    "ReasoningEffort": null,
    "ResponseTimeout": "00:02:00",
    "MaxConcurrentTurns": null,          // optional cap across the app
    "EagerStart": false,                 // start with the host instead of on first use
    "InProcess": false,                  // host the runtime in this process (experimental)
    "SessionRetention": null,            // delete sessions idle this long, for example "7.00:00:00"

    "GitHubToken": null,                 // service identity for the SDK-managed runtime; use a secret store
    "Endpoint": null,                    // shared runtime; null = SDK-managed runtime
    "ConnectionToken": null,             // required when Endpoint is not loopback
    "ConnectionName": "copilot",         // ConnectionStrings:{name} to read

    "McpServers": { "docs": { "Type": "http", "Url": "https://docs.internal/mcp", "Tools": [ "search" ] } },
    "Provider": null,                    // BYOK, the SDK's ProviderConfig shape

    "Agents": {
      "triage": { "Model": "gpt-5.4-mini", "Instructions": "You triage support tickets." }
    }
  }
}
```

Environment variables follow the usual .NET convention, for example
`Copilot__Model=claude-haiku-4.5` or `Copilot__GitHubToken=<token>`.

## Options reference

| Option | Default | Description |
| --- | --- | --- |
| `Model` | runtime default | The model, for example `gpt-5.4`. |
| `Instructions` | none | Appended to the agent's system message (on new and resumed sessions). |
| `ReasoningEffort` | none | For models that support it, for example `low`, `medium`, `high`. |
| `MessageSource` | none (runtime default `user`) | `user`, `system`, or `agent-<id>` provenance label for prompts. Not an authenticated identity; see [Message source](chat-clients.html#message-source). |
| `ResponseTimeout` | `00:02:00` | Maximum time for a turn. The turn is aborted and `TimeoutException` is thrown when it elapses. |
| `MaxConcurrentTurns` | none | Optional cap on concurrent turns across the app. Extra turns wait in a queue of up to 8× the limit. *Client-level.* |
| `EagerStart` | `false` | Start the client with the host instead of on first use. *Client-level.* |
| `InProcess` | `false` | Host the runtime in this process instead of a child process. Experimental in the SDK (`GHCP001`). Runtime OpenTelemetry export is not available in-process. Cannot be combined with `Endpoint`. *Client-level.* |
| `SessionRetention` | none | Delete sessions not modified for this long. See [Conversations](conversations.md#session-lifetime). *Client-level.* |
| `GitHubToken` | none | Service identity for the SDK-managed runtime. Cannot be combined with `Endpoint`. *Client-level.* |
| `Endpoint` | none | A shared, running runtime: `tcp://host:port`, `http(s)://host:port`, `host:port`, or a loopback port number. When unset, the SDK starts its bundled runtime. *Client-level.* |
| `ConnectionToken` | none | Shared secret for `Endpoint`; required when the endpoint is not loopback. *Client-level.* |
| `ConnectionName` | `copilot` | The connection string (`ConnectionStrings:{name}`) to read. |
| `McpServers` | empty | MCP servers keyed by name. See [MCP servers](mcp-servers.md). |
| `Provider` | none | Bring-your-own-key model provider, the SDK's `ProviderConfig`. See [BYOK providers](#byok-providers). |
| `GitHubTokenProvider` | none | Code only. Supplies a per-user GitHub token at the start of each turn. See [Runtime and identity](identity.md#per-user-identity). |
| `OnApproval` | none | Code only. Decides calls to `[RequiresApproval]` tools. See [Approvals and permissions](permissions.md). |
| `ConfigureClient(...)` | — | Code only. Configures the SDK's `CopilotClientOptions`. *Client-level.* |
| `ConfigureSession(...)` | — | Code only. Configures the SDK's `SessionConfigBase` for new and resumed sessions. |

*Client-level* settings are read from the default agent only, because every agent shares one
`CopilotClient` and runtime. `CopilotOptions.SectionName` is `"Copilot"` and
`CopilotOptions.DefaultConnectionName` is `"copilot"`.

## Binding order

For each agent, options are composed in this order; later steps override earlier ones:

1. The default agent's code delegates (`AddCopilot(o => ...)`).
2. The `Copilot` section.
3. For a named agent: its own delegates (`AddCopilot("triage", o => ...)`), then
   `Copilot:Agents:{name}`.
4. `ConnectionStrings:{ConnectionName}`, which only fills values that are still unset.

Because a named agent starts from the default agent's options, it inherits the default agent's
delegates, `McpServers`, and `ConfigureSession` actions, and adds its own. Tools are per agent.
See [Named agents](chat-clients.md#named-agents).

## Connection strings

A connection string is how a Copilot runtime started elsewhere is consumed, most often by an
[Aspire](https://aspire.dev/) AppHost. When `ConnectionStrings:copilot` (or the name in
`ConnectionName`) is present, `AddCopilot()` reads it with no extra code:

```json
{
  "ConnectionStrings": {
    "copilot": "Endpoint=tcp://localhost:5000;ConnectionToken=<secret>;Model=gpt-5.4"
  }
}
```

| Key | Fills |
| --- | --- |
| `Endpoint` | `Endpoint` |
| `ConnectionToken` | `ConnectionToken` |
| `Model` | `Model` |
| `Provider` | `Provider.Type` (a BYOK provider type, for example `openai` or `azure`) |
| `ProviderEndpoint` | `Provider.BaseUrl` |
| `ProviderKey` | `Provider.ApiKey` |
| `WireApi` | `Provider.WireApi` |

- Keys are case-insensitive. Unknown keys are logged as a warning and ignored, so the producer
  and consumer can be upgraded independently.
- A bare absolute URI, such as `tcp://localhost:5000`, is accepted as the endpoint.
- Values only fill settings that are still unset; explicit `Copilot:*` values win.
- `Endpoint` and `ConnectionToken` are applied together, and only when no `Endpoint` is set, so
  an explicit endpoint is never paired with another runtime's token.
- An unparseable connection string fails at startup with
  `ConnectionStrings:{name} is not a valid Copilot connection string`.

With Aspire, the AppHost writes this connection string for you, and `CopilotSdk.Aspire` adds
`builder.AddCopilotClient("copilot")`, which calls `AddCopilot` with `ConnectionName` set and
adds Aspire's health and telemetry defaults. See the
[Copilot Agents for Aspire](https://brunoborges.github.io/copilot-sdk-integrations/aspire/)
documentation.

## BYOK providers

`Provider` uses the SDK's `ProviderConfig` shape, so it binds from configuration:

```json
{
  "Copilot": {
    "Model": "gpt-5.4",
    "Provider": {
      "Type": "openai",
      "BaseUrl": "https://my-endpoint.example.com/v1",
      "ApiKey": "<from a secret store>"
    }
  }
}
```

`Provider:BaseUrl` is required when `Provider` is set. With a BYOK provider, the
[health check](observability.md#health-checks) reports Healthy even when the runtime is not
signed in to GitHub.

## Validation

Options are validated when the host starts (`ValidateOnStart`), with messages that name the
key to fix:

- a non-loopback `Endpoint` requires `ConnectionToken`;
- `Endpoint` must be `tcp://host:port`, `http(s)://host:port`, `host:port`, or a port number;
- `GitHubToken` cannot be combined with `Endpoint` (a shared runtime manages its own sign-in);
- `InProcess` cannot be combined with `Endpoint`;
- `Provider` requires `BaseUrl`;
- an HTTP MCP server needs an absolute http(s) `Url`, a stdio server needs a `Command`, and
  unknown `Type` values are rejected;
- `ResponseTimeout`, `MaxConcurrentTurns`, `SessionRetention`, and MCP `Timeout` must be positive.

For a named agent, messages use the `Copilot:Agents:{name}:` prefix.

## Escape hatches

Anything not covered by `CopilotOptions` is set on the SDK types directly, so there is no
translation layer to learn:

```cs
builder.Services.AddCopilot(o => o
    .ConfigureClient(client => client.BaseDirectory = "/var/copilot")            // CopilotClientOptions
    .ConfigureSession(session => session.ExcludedTools = ["web_fetch"]));        // SessionConfigBase (create and resume)
```

Both run after the integration's own settings, so they can replace them. `ConfigureClient` is
honored on the default agent only. `ConfigureSession` can also replace the default
[permission handler](permissions.md#replacing-the-handler).
