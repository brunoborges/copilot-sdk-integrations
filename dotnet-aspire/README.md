# copilot-aspire

[Aspire](https://aspire.dev/) integration for the
[GitHub Copilot SDK for .NET](https://github.com/github/copilot-sdk/tree/main/dotnet).

This is the **orchestration** layer: it runs, secures, and wires the Copilot
runtime in an Aspire AppHost, and connects service projects to it. Application
features (agents, `IChatClient`, tools, permissions, testing) come from the
[core .NET integration](../dotnet-core/), which this package builds on, the same
way `Aspire.Npgsql` builds on `Npgsql`.

The client package is optional. A service that uses only core can still read
the AppHost's connection string. The client package adds Aspire's health-check
and telemetry defaults.

> **Status: design phase.** No code or packages yet. Read the
> [design document](docs/design.md) and open an issue or PR with feedback.

## What it will look like

AppHost:

```csharp
var copilot = builder.AddCopilot("copilot");          // headless Copilot runtime, on the dashboard
var chat    = copilot.AddModel("chat", "gpt-5.4");

builder.AddProject<Projects.Api>("api").WithReference(chat).WaitFor(chat);
```

Service:

```csharp
builder.AddCopilotClient("chat")                     // Aspire: connection string, health, telemetry
       .AddAgent("support", agent => agent
           .WithInstructions("You help customers with orders.")
           .WithTools<OrderTools>())
       .AddKeyedChatClient("support");                 // core API: keyed IChatClient

app.MapPost("/ask", async ([FromKeyedServices("support")] IChatClient chat, string q) =>
    (await chat.GetResponseAsync(q)).Text);
```

Highlights of the design:

- **AppHost resources** for the Copilot runtime and model selection, running the
  SDK-bundled runtime locally (no install) or as a container for deployment.
- **Connection strings, secrets, and health checks** the Aspire way, with the
  GitHub token as a secret parameter and a generated connection token.
- **End-to-end traces** on the Aspire dashboard: app request → Copilot turn →
  model call → tool callback.
- **MCP servers and BYOK providers** wired by reference from other Aspire
  resources (projects, containers, Azure AI Foundry, OpenAI, Ollama).
- **A thin client package** that maps the connection string onto core options
  and returns the core builder, so service code is identical with or without
  Aspire.

## License

[MIT](../LICENSE).
