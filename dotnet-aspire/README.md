# copilot-aspire

[Aspire](https://aspire.dev/) integration for the
[GitHub Copilot SDK for .NET](https://github.com/github/copilot-sdk/tree/main/dotnet).

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
builder.AddCopilotClient("chat")
       .AddAgent("support", agent => agent
           .WithInstructions("You help customers with orders.")
           .WithTools<OrderTools>())
       .AddChatClient();                               // Microsoft.Extensions.AI IChatClient

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
- **`IChatClient` and Agent Framework `AIAgent`** surfaces over named agent
  profiles, so existing `Microsoft.Extensions.AI` code works unchanged.
- **DI services as tools**, each call in its own scope; MCP servers and BYOK
  providers wired from other Aspire resources.
- **Safe defaults**: deny-by-default permissions, isolated client mode, and
  explicit tool and MCP allow-lists.

See [the Spring Boot starter](../spring-boot/) for the equivalent Java integration.

## License

[MIT](../LICENSE).
