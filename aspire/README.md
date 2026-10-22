# copilot-aspire

[Aspire](https://aspire.dev/) integration for the
[GitHub Copilot SDK for .NET](https://github.com/github/copilot-sdk/tree/main/dotnet).

This is the **orchestration** layer: it runs, secures, and wires the Copilot
runtime in an Aspire AppHost, and connects service projects to it. Application
features (`IChatClient`, conversations, tools, permissions) come from the
[core .NET integration](../dotnet/), which this package builds on, the same
way `Aspire.Npgsql` builds on `Npgsql`.

The client package is optional. A service that uses only core can still read
the AppHost's connection string. The client package adds Aspire's health-check
and telemetry defaults.

> **Status: Preview — implemented, not yet published to NuGet.** Build it from
> source (below). Read the [design document](docs/design.md) and open an issue
> or PR with feedback.

## What it looks like

AppHost:

```csharp
var copilot = builder.AddCopilot("copilot");          // headless Copilot runtime, on the dashboard
var chat    = copilot.AddModel("chat", "gpt-5.4");

builder.AddProject<Projects.Api>("api").WithReference(chat).WaitFor(chat);
```

Service:

```csharp
builder.AddCopilotClient("chat", o => o.Instructions = "You help customers with orders.")
       .WithTools<OrderTools>();                       // core API from here on

app.MapPost("/ask", async (IChatClient chat, string q) => (await chat.GetResponseAsync(q)).Text);
```

Plain `WithReference(chat)` is all the wiring needed: MCP server and
reasoning-effort settings are injected into referencing projects automatically.

Highlights:

- **AppHost resources** for the Copilot runtime and model selection, running the
  SDK-bundled runtime locally (no install) or as a container for deployment.
- **Connection strings, secrets, and health checks** the Aspire way, with the
  GitHub token as a secret parameter and a generated connection token.
- **End-to-end traces** on the Aspire dashboard: app request → Copilot turn →
  model call → tool callback.
- **MCP servers and BYOK providers** wired by reference from other Aspire
  resources (`WithMcpServer`, `WithProvider`). Keyless (Entra ID) providers
  aren't supported yet.
- **A thin client package** that maps the connection string onto core options
  and returns the core builder, so service code is identical with or without
  Aspire.

## Getting started

Prerequisites: the .NET 10 SDK and a GitHub account with Copilot access. Locally
the runtime uses your signed-in Copilot CLI user; in a container (and in publish
mode) it needs a GitHub token, supplied with `WithGitHubToken(...)` or the
generated `{name}-github-token` secret parameter. The hosting package targets
`net8.0` and finds the runtime via `COPILOT_CLI_PATH`, then the SDK-bundled
runtime, then `copilot` on `PATH` (override with `WithCliPath`).

From `aspire/`:

```bash
dotnet build CopilotSdk.Aspire.slnx
dotnet test --solution CopilotSdk.Aspire.slnx
```

A sample AppHost is still to come; see the
[core samples](../dotnet/samples/) for service code.

## License

[MIT](../LICENSE).
