---
title: .NET services
---

# .NET services

.NET services have two options. `CopilotSdk.Aspire` adds `AddCopilotClient`, which follows the
usual Aspire client integration pattern. The [core .NET integration](https://brunoborges.github.io/copilot-sdk-integrations/dotnet/)
also reads the connection string on its own, without the Aspire package.

Both register the core `IChatClient`. Service code is the same with or without Aspire.

## `AddCopilotClient`

AppHost:

```cs
var chat = builder.AddCopilot("copilot").AddModel("chat", "gpt-5.4");
builder.AddProject<Projects.Api>("api").WithReference(chat).WaitFor(chat);
```

Service:

```cs
var builder = WebApplication.CreateBuilder(args);

builder.AddCopilotClient("chat", o => o.Instructions = "You help customers with orders.")
       .WithTools<OrderTools>();

var app = builder.Build();
app.MapPost("/ask", async (IChatClient chat, string q) => (await chat.GetResponseAsync(q)).Text);
app.Run();
```

```cs
public static ICopilotBuilder AddCopilotClient(
    this IHostApplicationBuilder builder,
    string connectionName,
    Action<CopilotOptions>? configure = null,
    Action<CopilotClientSettings>? configureSettings = null)
```

| Parameter | Description |
| --- | --- |
| `connectionName` | The runtime or model resource name in the AppHost. The client reads `ConnectionStrings:{connectionName}`. |
| `configure` | Code defaults for the agent. Configuration in the `Copilot` section overrides them. |
| `configureSettings` | Overrides for the Aspire settings below. |

`AddCopilotClient` lives in the `Microsoft.Extensions.Hosting` namespace. It returns the core
`ICopilotBuilder`, so tools, hooks, and the rest of the core API work as usual. On top of core it:

- sets `CopilotOptions.ConnectionName` to the connection name, so core reads the endpoint, connection
  token, model, and BYOK provider from the connection string;
- registers the health check `copilot_{connectionName}` with the `ready` tag;
- adds the `Experimental.Microsoft.Extensions.AI` trace source and meter to OpenTelemetry;
- fails at startup with `ConnectionStrings:{name} not found. Did you call WithReference...` when the
  connection string is missing.

The MCP server and reasoning-effort variables from the AppHost (`Copilot__McpServers__…`,
`Copilot__ReasoningEffort`) bind to the core `Copilot` configuration section with no code.

## Keyed clients

Use `AddKeyedCopilotClient` for several models in one service:

```cs
builder.AddKeyedCopilotClient("chat");
builder.AddKeyedCopilotClient("fast");

app.MapPost("/summarize", async ([FromKeyedServices("fast")] IChatClient fast, string text) =>
    (await fast.GetResponseAsync($"Summarize: {text}")).Text);
```

The connection name is also the service key and the core agent name. Configuration in
`Copilot:Agents:{connectionName}` overrides the code defaults. All keyed clients share one
`CopilotClient`, so they must use models on the same runtime. Keyed clients on different runtimes
aren't supported.

## Settings

`CopilotClientSettings` binds from `Aspire:Copilot:Client`, then from
`Aspire:Copilot:Client:{connectionName}`, then from `configureSettings`.

| Setting | Default | Description |
| --- | --- | --- |
| `DisableHealthChecks` | `false` | Skip the `copilot_{connectionName}` health check. |
| `DisableTracing` | `false` | Skip the `Experimental.Microsoft.Extensions.AI` trace source. |
| `DisableMetrics` | `false` | Skip the `Experimental.Microsoft.Extensions.AI` meter. |
| `HealthCheckTimeout` | Health check service default | Timeout of the health check. |

```json
{
  "Aspire": {
    "Copilot": {
      "Client": {
        "chat": { "HealthCheckTimeout": "00:00:05" }
      }
    }
  }
}
```

## Without the Aspire package

The core integration reads `ConnectionStrings:copilot` by default, or the name in
`CopilotOptions.ConnectionName`:

```cs
builder.Services.AddCopilot(o => o.ConnectionName = "chat");
```

This gives the same connection without the Aspire health check and telemetry defaults. See the
[.NET documentation](https://brunoborges.github.io/copilot-sdk-integrations/dotnet/) for the core
API: agents, tools, conversations, and configuration.

## Target frameworks

`CopilotSdk.Aspire` targets `net10.0` and `net8.0`. The AppHost needs the .NET 10 SDK regardless
of the service's target framework.
