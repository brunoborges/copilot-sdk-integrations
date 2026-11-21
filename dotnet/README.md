# copilot-dotnet

.NET integration for the
[Microsoft Copilot SDK for .NET](https://github.com/microsoft/copilot-sdk/tree/main/dotnet),
for business applications built on the Generic Host and ASP.NET Core: web APIs,
worker services, and back-office apps.

📖 **[Documentation](https://brunoborges.github.io/copilot-sdk-integrations/dotnet/)**

> **Status: Preview — released to GitHub Packages, not nuget.org.** Install it
> from the GitHub Packages NuGet feed or build it from source (below). Read the
> [design document](docs/design.md) and open an issue or PR with feedback.

## What it looks like

```csharp
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

Typed results come from the same interface:
`await chat.GetResponseAsync<TicketTriage>(prompt)`.

The design rule: **every feature must delete code from the application**,
compared with calling the SDK directly. The integration handles what every app
would otherwise write by hand:

- the `CopilotClient` lifecycle;
- create-or-resume per conversation, without concurrent sends to one session;
- a DI scope per tool call, with `HttpContext`, `Activity`, and logging scopes
  intact;
- a safe default permission handler (your tools run, `[RequiresApproval]` tools
  go through `OnApproval`, everything else is denied);
- configuration, logging, health checks, and OpenTelemetry.

Everything else uses the SDK's own types, and `CopilotClient` is injectable for
full SDK access. See the [design document](docs/design.md).

## Getting started

Prerequisites: the .NET 10 SDK, and a GitHub account with Copilot access.
Locally, the integration uses the user signed in to the
[Copilot CLI](https://docs.github.com/copilot/how-tos/set-up/install-copilot-cli);
nothing to configure. Elsewhere, supply a service token either as
`Copilot:GitHubToken` (user secrets, a secret store, or
`export Copilot__GitHubToken=<token>`) or through the standard
`COPILOT_GITHUB_TOKEN`, `GH_TOKEN`, or `GITHUB_TOKEN` environment variables;
or point `Copilot:Endpoint` at a shared runtime. The SDK downloads its bundled runtime on
first build.

Add the GitHub Packages feed, then the package. GitHub Packages requires a
token even for public packages: a personal access token (classic) with the
`read:packages` scope, which the commands below read from `GITHUB_TOKEN`. See
[Installing released packages](../README.md#installing-released-packages) and
[Releases](https://github.com/brunoborges/copilot-sdk-integrations/releases)
for the latest version.

```bash
dotnet nuget add source https://nuget.pkg.github.com/brunoborges/index.json \
  --name copilot-sdk-integrations --username YOUR_GITHUB_USERNAME \
  --password "$GITHUB_TOKEN" --store-password-in-clear-text
dotnet add package CopilotSdk.Extensions.Hosting --version 0.1.0
```

To build from source, run the tests, or run a sample, from `dotnet/`:

```bash
dotnet build CopilotSdk.slnx
dotnet test --solution CopilotSdk.slnx                      # unit tests; no Copilot access needed
COPILOT_LIVE_TESTS=1 dotnet test --solution CopilotSdk.slnx # also run live tests with the signed-in user
dotnet run --project samples/SupportApi                     # http://localhost:5180
```

## Samples

[`samples/`](samples/) has a support API, the same API written against the raw
SDK, and an invoice-processing worker. The integration version of the support
API is 36 lines of C#; the raw-SDK version is 218
([comparison](samples/README.md#supportapi-vs-supportapirawsdk)).

Running in [Aspire](https://aspire.dev/)? The [Aspire integration](../aspire/)
orchestrates the Copilot runtime and builds on this package.

## License

[MIT](../LICENSE).
