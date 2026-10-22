# copilot-dotnet

.NET integration for the
[GitHub Copilot SDK for .NET](https://github.com/github/copilot-sdk/tree/main/dotnet),
for business applications built on the Generic Host and ASP.NET Core: web APIs,
worker services, and back-office apps.

> **Status: design phase.** No code or packages yet. Read the
> [design document](docs/design.md) and open an issue or PR with feedback.

## What it will look like

```csharp
builder.Services.AddCopilot(o => o.Instructions = "You help customers with their orders.")
    .WithTools<OrderTools>();                          // DI service methods become tools

app.MapPost("/support", async (IChatClient chat, SupportRequest req, CancellationToken ct) =>
{
    var response = await chat.GetResponseAsync(req.Message, new() { ConversationId = req.ConversationId }, ct);
    return new { response.Text, response.ConversationId };
});
```

Typed results come from the same interface:
`await chat.GetResponseAsync<TriageResult>(prompt)`.

The design rule: **every feature must delete code from the application**,
compared with calling the SDK directly. The integration handles what every app
would otherwise write by hand:

- the `CopilotClient` lifecycle;
- create-or-resume per conversation, without concurrent sends to one session;
- a DI scope per tool call, with `HttpContext`, `Activity`, and logging scopes
  intact;
- a safe default permission handler (your tools run, everything else is denied);
- configuration, logging, health checks, and OpenTelemetry.

Everything else uses the SDK's own types, and `CopilotClient` is injectable for
full SDK access. See the [design document](docs/design.md), including a
side-by-side comparison with the raw SDK.

Running in [Aspire](https://aspire.dev/)? The [Aspire integration](../dotnet-aspire/)
orchestrates the Copilot runtime and builds on this package.

## License

[MIT](../LICENSE).
