# copilot-dotnet

.NET integration for the
[GitHub Copilot SDK for .NET](https://github.com/github/copilot-sdk/tree/main/dotnet),
for business applications built on the Generic Host and ASP.NET Core: web APIs,
worker services, and back-office apps.

> **Status: design phase.** No code or packages yet. Read the
> [design document](docs/design.md) and open an issue or PR with feedback.

## What it will look like

Register an agent and use it as a standard
[Microsoft.Extensions.AI](https://learn.microsoft.com/dotnet/ai/microsoft-extensions-ai)
`IChatClient`:

```csharp
builder.Services.AddCopilot()                          // binds the "Copilot" config section
    .AddAgent("support", agent => agent
        .WithInstructions("You help customers with their orders.")
        .WithTools<OrderTools>())                      // DI service methods become tools
    .AddKeyedChatClient("support");

app.MapPost("/support", async ([FromKeyedServices("support")] IChatClient chat, SupportRequest req) =>
    (await chat.GetResponseAsync(req.Message, new() { ConversationId = req.ConversationId })).Text);
```

Or declare a typed agent and let a source generator implement it:

```csharp
[CopilotAgent("triage", Instructions = "You triage support tickets for Contoso.")]
public partial interface ITicketTriage
{
    [Prompt("Classify this ticket. Subject: {subject}. Body: {body}")]
    Task<TriageResult> ClassifyAsync(string subject, string body, CancellationToken ct = default);
}

builder.Services.AddCopilot().AddTypedAgent<ITicketTriage>();
```

Highlights of the design:

- **One registration** (`AddCopilot()`) with options bound from configuration,
  validated at startup, and a managed client lifecycle.
- **Four surfaces over the same agent profile**: `IChatClient`, source-generated
  typed agents, full-fidelity conversations, and Agent Framework `AIAgent`.
- **DI services as tools**, each call in its own scope, with access to the
  calling user, tenant, and conversation.
- **Per-user identity** and BYOK providers for multi-tenant apps.
- **Governance**: deny-by-default permissions, tool allow-lists,
  human-in-the-loop approvals, and audit hooks.
- **Production behavior**: concurrency limits, cancellation, resilience, SSE
  streaming endpoints, health checks, and OpenTelemetry traces and metrics.
- **Offline testing** with a scripted runtime and `WebApplicationFactory`
  helpers.

Running in [Aspire](https://aspire.dev/)? The [Aspire integration](../dotnet-aspire/)
orchestrates the Copilot runtime and builds on this package. See
[the Spring Boot starter](../spring-boot/) for the equivalent Java integration.

## License

[MIT](../LICENSE).
