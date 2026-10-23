---
title: Documentation
---

# Copilot for .NET

`CopilotSdk.Extensions.Hosting` connects the
[GitHub Copilot SDK for .NET](https://github.com/github/copilot-sdk/tree/main/dotnet)
to the .NET Generic Host: ASP.NET Core APIs, worker services, and back-office apps. One call,
`AddCopilot`, registers a `Microsoft.Extensions.AI` `IChatClient` backed by Copilot, and owns
the client lifecycle, conversations, tools with a DI scope per call, a safe default permission
handler, configuration, health checks, and OpenTelemetry.

> **Status:** preview. The library is implemented and tested, but it has not been published to
> NuGet. Build it from the [repository](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/dotnet)
> and reference the project, as the samples do. See [Getting started](getting-started.md).

```cs
builder.Services.AddCopilot(o =>
{
    o.Instructions = "You are Contoso's customer support assistant.";
    o.OnApproval = call => ValueTask.FromResult(call.GetArgument<decimal>("amount") < 100);
}).WithTools<OrderTools>();                            // DI service methods become tools

app.MapPost("/chat", async (IChatClient chat, ChatRequest req, CancellationToken ct) =>
{
    var response = await chat.GetResponseAsync(req.Message, new() { ConversationId = req.ConversationId }, ct);
    return new ChatReply(response.Text, response.ConversationId);
});
```

## Baseline

| Component | Version |
| --- | --- |
| .NET | `net10.0` and `net8.0` (the library multi-targets; the samples and tests target .NET 10) |
| .NET SDK to build the repository | 10.0 (`global.json`: `10.0.100`, roll forward to the latest feature band) |
| Copilot SDK (`GitHub.Copilot.SDK`) | `1.0.26` |
| `Microsoft.Extensions.AI` | `10.11.0` |
| Hosting, options, and health-check abstractions | `10.0.13` |
| A GitHub account | with Copilot access (or a [BYOK provider](configuration.md#byok-providers)) |

## Choose your integration

| Package | Use it when | Depends on |
| --- | --- | --- |
| `CopilotSdk.Extensions.Hosting` | You build an ASP.NET Core API, a worker service, or any Generic Host app and want an injectable `IChatClient` backed by Copilot. | `GitHub.Copilot.SDK`, `Microsoft.Extensions.AI`, hosting abstractions |
| `CopilotSdk.Aspire` and `CopilotSdk.Aspire.Hosting` | Your app runs in an [Aspire](https://aspire.dev/) AppHost, which starts the Copilot runtime and hands its address to your services. | `CopilotSdk.Extensions.Hosting`, Aspire |

The Aspire packages build on this one, the same way `Aspire.Npgsql` builds on `Npgsql`. They
are documented on their own site:
[Copilot for Aspire](https://brunoborges.github.io/copilot-sdk-integrations/aspire/).

## Aspire and connection strings

An Aspire AppHost passes the runtime to your service as a connection string,
`ConnectionStrings:copilot`. `AddCopilot()` reads it with no extra code, so the same
application runs with the bundled runtime locally, against a shared runtime, or under Aspire:

| Setup | Runtime | Registration |
| --- | --- | --- |
| Single app | Bundled runtime, started by the SDK | `AddCopilot()` |
| Shared runtime | Your own headless runtime | `AddCopilot()` + `Copilot:Endpoint` |
| Aspire AppHost | AppHost resource | `AddCopilot()` reads `ConnectionStrings:copilot`, or `builder.AddCopilotClient("copilot")` from `CopilotSdk.Aspire` for Aspire's health and telemetry defaults |

See [Connection strings](configuration.md#connection-strings) for the keys, and the
[Aspire documentation](https://brunoborges.github.io/copilot-sdk-integrations/aspire/) for the
AppHost side.

## Read by chapter

| Chapter | Covers |
| --- | --- |
| [Getting started](getting-started.md) | Prerequisites, referencing the project, a first API with a tool, and running it. |
| [Configuration](configuration.md) | Every `CopilotOptions` setting, the `Copilot` section, binding order, connection strings, validation, and escape hatches. |
| [Chat clients and agents](chat-clients.md) | `AddCopilot`, `IChatClient`, named agents with keyed services, per-call `ChatOptions`, and the injectable `CopilotClient`. |
| [Tools](tools.md) | `WithTools<T>()`, `WithTool(...)`, naming, injected parameters, the DI scope per call, and errors. |
| [Approvals and permissions](permissions.md) | The default permission handler, `[RequiresApproval]`, `OnApproval`, and replacing the handler. |
| [Conversations](conversations.md) | `ChatOptions.ConversationId`, create-or-resume, locking, history rules, and ownership. |
| [Streaming and structured output](streaming.md) | `GetStreamingResponseAsync`, server-sent events, `GetResponseAsync<T>`, usage, timeouts, and cancellation. |
| [MCP servers](mcp-servers.md) | HTTP and stdio MCP servers from configuration, and their tool allow-lists. |
| [Runtime and identity](identity.md) | Signed-in user, service tokens, per-user `GitHubTokenProvider`, BYOK, the client mode, and lifecycle. |
| [Health checks and telemetry](observability.md) | `AddHealthChecks().AddCopilot()`, logging, OpenTelemetry traces and metrics, and runtime telemetry. |
| [Samples](samples.md) | `SupportApi`, `SupportApi.RawSdk`, and `InvoiceWorker`, and what the integration removes. |
| [Testing](testing.md) | Faking `IChatClient`, testing tool classes, and the repository's unit and live tests. |

## Design principles

- **Every feature must delete code from the application.** If a feature adds concepts without
  removing code you would otherwise write against the SDK, it is not in the library.
- **One primary surface, one escape hatch.** You call agents through `IChatClient`; for
  anything else, `CopilotClient` and the SDK's own option types are injectable or configurable.
- **Safe by default.** Built-in runtime tools (shell, file writes, URL fetches) are not offered,
  and the default permission handler approves only your tools.
- **Server-side history.** The Copilot runtime keeps the conversation; your app keeps only its id.

The full rationale is in the
[design document](https://github.com/brunoborges/copilot-sdk-integrations/blob/main/dotnet/docs/design.md).

## Build and preview this site

The site is generated from Markdown in `dotnet/site/markdown` by a small Node.js script.

```bash
cd dotnet/site
npm ci
npm run build
open dist/index.html
```

The build fails on broken internal links or anchors.
