# copilot-aspire

[Aspire](https://aspire.dev/) integration for the
[Microsoft Copilot SDKs](https://github.com/microsoft/copilot-sdk).

📖 **[Documentation](https://brunoborges.github.io/copilot-sdk-integrations/aspire/)**

This is the **orchestration** layer: it runs, secures, and wires the Copilot
runtime in an Aspire AppHost, and connects service projects to it. Application
features (`IChatClient`, conversations, tools, permissions) come from the
[core .NET integration](../dotnet/), which this package builds on, the same
way `Aspire.Npgsql` builds on `Npgsql`.

The client package is optional. A service that uses only core can still read
the AppHost's connection string. The client package adds Aspire's health-check
and telemetry defaults.

The AppHost side is **polyglot**. A service written in any language that Aspire
can orchestrate can use the runtime, whether it uses the Spring Boot, Quarkus or
Node.js integration, or a raw Copilot SDK for Java, Node.js or Python.
`WithReference(chat)` injects language-neutral environment variables for this:
`CHAT_HOST`, `CHAT_PORT`, `CHAT_ENDPOINT`, `CHAT_CONNECTIONTOKEN` and `CHAT_MODEL`.

> **Status: Preview — released to GitHub Packages, not nuget.org.** Install it
> from the GitHub Packages NuGet feed or build it from source (below). Read the
> [design document](docs/design.md) and open an issue or PR with feedback.

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

Add the GitHub Packages feed, then `CopilotSdk.Aspire.Hosting` to the AppHost
and, optionally, `CopilotSdk.Aspire` to each .NET service. GitHub Packages
requires a token even for public packages: a personal access token (classic)
with the `read:packages` scope, which the commands below read from
`GITHUB_TOKEN`. See
[Installing released packages](../README.md#installing-released-packages) and
[Releases](https://github.com/brunoborges/copilot-sdk-integrations/releases)
for the latest version.

```bash
dotnet nuget add source https://nuget.pkg.github.com/brunoborges/index.json \
  --name copilot-sdk-integrations --username YOUR_GITHUB_USERNAME \
  --password "$GITHUB_TOKEN" --store-password-in-clear-text
dotnet add package CopilotSdk.Aspire.Hosting --version 0.1.0   # in the AppHost
dotnet add package CopilotSdk.Aspire --version 0.1.0           # in each .NET service
```

Services in other languages don't use these packages. To install this
repository's Java, Node.js, or Python integrations for them, see the same
[install section](../README.md#installing-released-packages).

To build from source and run the tests, from `aspire/`:

```bash
dotnet build CopilotSdk.Aspire.slnx
dotnet test --solution CopilotSdk.Aspire.slnx
```

A sample AppHost is still to come; see the
[core samples](../dotnet/samples/) for service code.

## License

[MIT](../LICENSE).
