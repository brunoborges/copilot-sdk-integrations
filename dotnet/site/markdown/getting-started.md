---
title: Getting started
---

# Getting started

This chapter builds a small ASP.NET Core API that answers questions about orders, with one
tool, multi-turn conversations, and a health check.

**In this chapter**

- [Prerequisites](#prerequisites)
- [Reference the library](#reference-the-library)
- [Register Copilot](#register-copilot)
- [Add a tool](#add-a-tool)
- [Call the agent](#call-the-agent)
- [Run it](#run-it)
- [Next steps](#next-steps)

## Prerequisites

- The .NET 10 SDK (the library also targets .NET 8).
- A GitHub account with Copilot access.

Locally, nothing else is needed: the SDK starts its bundled Copilot runtime (downloaded on
first build) and uses the user signed in to the
[Copilot CLI](https://docs.github.com/copilot/how-tos/set-up/install-copilot-cli). Elsewhere,
supply a service token as `Copilot:GitHubToken`, or through the `COPILOT_GITHUB_TOKEN`,
`GH_TOKEN`, or `GITHUB_TOKEN` environment variables. See [Runtime and identity](identity.md).

## Reference the library

The package is in preview and not on NuGet yet. Clone the repository and add a project
reference, the way the samples do:

```bash
git clone https://github.com/brunoborges/copilot-sdk-integrations.git
cd copilot-sdk-integrations/dotnet
dotnet build CopilotSdk.slnx
```

```xml
<ItemGroup>
  <ProjectReference Include="../copilot-sdk-integrations/dotnet/src/CopilotSdk.Extensions.Hosting/CopilotSdk.Extensions.Hosting.csproj" />
</ItemGroup>
```

The repository uses central package management (`dotnet/Directory.Packages.props`), which pins
`GitHub.Copilot.SDK` and `Microsoft.Extensions.AI`. The library brings these in transitively.

## Register Copilot

`AddCopilot` lives in the `Microsoft.Extensions.DependencyInjection` namespace, so it needs no
extra `using`. It registers an `IChatClient`, the shared `CopilotClient`, its lifecycle, and
the default permission handler:

```cs
using Microsoft.Extensions.AI;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddScoped<IOrderRepository, InMemoryOrderRepository>();
builder.Services.AddCopilot(o =>
{
    o.Instructions = "You are Contoso's customer support assistant. Use the tools to look up orders.";
}).WithTools<OrderTools>();
builder.Services.AddHealthChecks().AddCopilot();
```

Settings set in code are defaults. The `Copilot` configuration section overrides them, so you
can change the model without a rebuild:

```json
{
  "Copilot": {
    "Model": "gpt-5.4",
    "ResponseTimeout": "00:02:00"
  }
}
```

When `Model` is not set, the runtime's default model is used. The available models depend on
your Copilot plan. Every setting is listed in [Configuration](configuration.md).

## Add a tool

A tool class is a plain class. Public methods with `[Description]` become tools; the class is
resolved from a new DI scope for every call, so it can take scoped services such as a
`DbContext`:

```cs
using System.ComponentModel;

public sealed class OrderTools(IOrderRepository orders)
{
    [Description("Looks up an order by its id.")]
    public Order? LookupOrder([Description("The order id, for example 1001.")] int orderId) => orders.Find(orderId);
}
```

The model sees this tool as `lookup_order`. See [Tools](tools.md).

## Call the agent

Inject `IChatClient` and pass the conversation id back and forth. The first call creates a
conversation; `response.ConversationId` continues it:

```cs
var app = builder.Build();

app.MapPost("/chat", async (IChatClient chat, ChatRequest req, CancellationToken ct) =>
{
    var response = await chat.GetResponseAsync(req.Message, new() { ConversationId = req.ConversationId }, ct);
    return new ChatReply(response.Text, response.ConversationId);
});

app.MapHealthChecks("/health");

app.Run();

public sealed record ChatRequest(string Message, string? ConversationId = null);
public sealed record ChatReply(string Reply, string? ConversationId);
```

## Run it

Replace `5180` with the port your app listens on:

```bash
dotnet run
curl -H 'content-type: application/json' -d '{"message":"What is the status of order 1002?"}' localhost:5180/chat
# => {"reply":"Order 1002 (4K monitor, $420.00) has shipped.","conversationId":"7ada513d-..."}
curl -H 'content-type: application/json' -d '{"message":"Which product was it?","conversationId":"7ada513d-..."}' localhost:5180/chat
```

The complete version of this app, with a refund tool behind an approval policy, streaming, and
structured output, is the [`SupportApi` sample](samples.md#supportapi):

```bash
cd dotnet
dotnet run --project samples/SupportApi            # http://localhost:5180
```

## Next steps

- Gate risky tools with [approvals](permissions.md).
- Stream replies and return typed results: [Streaming and structured output](streaming.md).
- Run several agents with different instructions: [Chat clients and agents](chat-clients.md#named-agents).
- Deploy with a service token, per-user tokens, or a shared runtime: [Runtime and identity](identity.md).
