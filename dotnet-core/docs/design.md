# Copilot SDK for .NET applications: design

Status: **Draft** · Targets: .NET 10 (`net8.0` where practical),
`GitHub.Copilot.SDK` 1.0.x, `Microsoft.Extensions.AI` 10.x.

This document proposes an integration of the
[GitHub Copilot SDK for .NET](https://github.com/github/copilot-sdk/tree/main/dotnet)
for business applications on the .NET Generic Host: ASP.NET Core APIs, worker
services, and Blazor apps.

It is independent of Aspire. The [Aspire integration](../../dotnet-aspire/docs/design.md)
runs the Copilot runtime in an AppHost and builds on this package, the same way
`Aspire.Npgsql` builds on `Npgsql`.

---

## 1. Design rule: simpler than the SDK, or it doesn't ship

The SDK is already easy to call. This integration exists only to remove code
that every business app would otherwise write. Each feature must pass one test:
**does it delete code from the application?** If a feature adds concepts without
deleting code, it belongs in a sample, in documentation, or in the SDK itself.

What the raw SDK leaves to every application, and what this integration does
about it:

| Every app would write… | The integration… |
| --- | --- |
| A singleton `CopilotClient` plus a hosted service to start it, stop it, and force-stop it on shutdown | Owns the client lifecycle |
| Create-or-resume logic per request, plus locking so two requests don't send to the same session at once | Maps conversations to `ChatOptions.ConversationId` |
| Tool delegates that open a DI scope by hand to reach a `DbContext` | Creates a DI scope per tool call |
| Code to capture `HttpContext.User`, `Activity`, and logging scopes before a turn, because tool callbacks run on the SDK's RPC thread | Flows the caller's `ExecutionContext` into tool calls |
| A permission handler. Without one, requests stay pending and the turn hangs | Ships a safe default handler |
| Event handling to turn session events into a reply, a typed result, or a stream | Exposes `IChatClient` (`GetResponseAsync`, `GetResponseAsync<T>`, streaming) |
| Configuration binding and wiring for the logger and OpenTelemetry | Binds the `Copilot` section and wires logging and telemetry |

Everything else uses the SDK's own types, so SDK documentation keeps applying.

### Non-goals

- A second API over `SessionConfig`. Advanced settings are set on the SDK types
  directly (§3.4).
- Several ways to call an agent. There is one primary surface (`IChatClient`)
  and one escape hatch (`CopilotClient`).
- Orchestrating the runtime for distributed apps (Aspire's job), and agent
  orchestration patterns (use Microsoft Agent Framework, §11).

---

## 2. Side by side

The support-assistant endpoint: answer a customer, with one tool that reads
their orders through EF Core, across multiple turns.

**Raw SDK** (abridged; lifecycle service and locking omitted):

```csharp
builder.Services.AddSingleton(sp => new CopilotClient(new CopilotClientOptions
{
    GitHubToken = builder.Configuration["Copilot:GitHubToken"],
    Logger = sp.GetRequiredService<ILogger<CopilotClient>>(),
}));
builder.Services.AddHostedService<CopilotClientLifetime>();      // start / stop / force-stop: ~30 lines

app.MapPost("/support", async (CopilotClient copilot, IServiceScopeFactory scopes,
    HttpContext http, SupportRequest req, CancellationToken ct) =>
{
    var customerId = http.User.GetCustomerId();                  // capture: tools run on another thread

    var lookupOrder = CopilotTool.DefineTool(
        async (int id) =>
        {
            await using var scope = scopes.CreateAsyncScope();   // DbContext needs a scope
            var orders = scope.ServiceProvider.GetRequiredService<OrderTools>();
            return await orders.LookupOrder(customerId, id);
        },
        new CopilotToolOptions { SkipPermission = true },         // or the permission request hangs
        new AIFunctionFactoryOptions { Name = "lookup_order", Description = "Looks up an order." });

    void Configure(SessionConfigBase c)
    {
        c.Model = builder.Configuration["Copilot:Model"];
        c.SystemMessage = /* append "You help customers with their orders." */;
        c.Tools = [lookupOrder];
        c.OnPermissionRequest = DenyEverythingElse;
    }

    // TODO: serialize concurrent requests for the same conversation
    await using var session = req.ConversationId is null
        ? await copilot.CreateSessionAsync(Apply(new SessionConfig(), Configure), ct)
        : await copilot.ResumeSessionAsync(req.ConversationId, Apply(new ResumeSessionConfig(), Configure), ct);

    var reply = await session.SendAndWaitAsync(req.Message, TimeSpan.FromMinutes(2), ct);
    return new { Text = reply?.Data.Content, ConversationId = session.SessionId };
});
```

**With the integration:**

```csharp
builder.Services.AddCopilot(o => o.Instructions = "You help customers with their orders.")
    .WithTools<OrderTools>();

app.MapPost("/support", async (IChatClient chat, SupportRequest req, CancellationToken ct) =>
{
    var response = await chat.GetResponseAsync(req.Message, new() { ConversationId = req.ConversationId }, ct);
    return new { response.Text, response.ConversationId };
});

public sealed class OrderTools(OrdersDbContext db, IHttpContextAccessor http)
{
    [Description("Looks up one of the current customer's orders.")]
    public Task<OrderDto?> LookupOrder(int id, CancellationToken ct) =>
        db.Orders.Where(o => o.Id == id && o.CustomerId == http.HttpContext!.User.GetCustomerId())
                 .Select(o => o.ToDto()).FirstOrDefaultAsync(ct);
}
```

The model and token come from configuration (`Copilot:Model`,
`Copilot:GitHubToken`). The tool is a plain class that can be unit-tested
without Copilot.

Every scenario sample in this repository ships both versions, so reviewers can
check the rule.

---

## 3. Registration and configuration

### 3.1 One call

```csharp
public static ICopilotBuilder AddCopilot(this IServiceCollection services,
    Action<CopilotOptions>? configure = null);                        // IChatClient
public static ICopilotBuilder AddCopilot(this IServiceCollection services, string name,
    Action<CopilotOptions>? configure = null);                        // keyed IChatClient "name"

public interface ICopilotBuilder
{
    IServiceCollection Services { get; }
    ICopilotBuilder WithTools<T>() where T : class;                    // methods with [Description]
    ICopilotBuilder WithTool(Delegate function, string? name = null, string? description = null);
}
```

`AddCopilot` registers `IChatClient`, the shared `CopilotClient`, its lifecycle,
and the default permission handler. Calling it again is safe; every agent shares
one client.

### 3.2 More than one agent

A single-agent app never sees a name. When an app needs more than one agent,
name them and inject keyed clients:

```csharp
builder.Services.AddCopilot(o => o.Instructions = "You help customers.")
    .WithTools<OrderTools>();
builder.Services.AddCopilot("triage", o => o.Instructions = "You triage support tickets.");

public sealed class TicketService([FromKeyedServices("triage")] IChatClient triage) { ... }
```

Named agents start from the default settings and override what they set.

### 3.3 Configuration

```jsonc
{
  "Copilot": {
    "Model": "gpt-5.4",
    "Instructions": "You help customers with their orders.",
    "ReasoningEffort": null,
    "ResponseTimeout": "00:02:00",
    "MaxConcurrentTurns": null,          // optional cap across the app

    "GitHubToken": null,                 // service identity; use a secret store
    "Endpoint": null,                    // tcp://host:port of a shared runtime; null = bundled runtime
    "ConnectionToken": null,             // required when Endpoint is not loopback

    "McpServers": { "docs": { "Type": "http", "Url": "https://docs.internal/mcp", "Tools": [ "search" ] } },
    "Provider": null,                    // BYOK, the SDK's ProviderConfig shape

    "Agents": {
      "triage": { "Model": "gpt-5.4-mini", "Instructions": "You triage support tickets." }
    }
  }
}
```

- Code sets defaults and configuration overrides them, so operators can change a
  model without a rebuild.
- `McpServers` and `Provider` use the SDK's shapes, not new ones.
- Validation runs at startup: a non-loopback `Endpoint` without a
  `ConnectionToken` fails fast.
- `Endpoint` and `ConnectionToken` can also come from a connection string,
  `ConnectionStrings:copilot` (`Endpoint=…;ConnectionToken=…;Model=…`), which is
  what an Aspire AppHost provides (§3.5). Set `o.ConnectionName` to read a
  different name. Explicit `Copilot:*` values win.

### 3.4 Escape hatches

Anything not covered is set on the SDK types directly, so there is no
translation layer to learn or keep in sync:

```csharp
builder.Services.AddCopilot(o =>
{
    o.ConfigureClient(client => client.BaseDirectory = "/var/copilot");          // CopilotClientOptions
    o.ConfigureSession(session => session.ExcludedTools = ["web_fetch"]);         // SessionConfigBase (create and resume)
});

app.MapGet("/models", (CopilotClient copilot) => copilot.ListModelsAsync());      // the shared client
```

`CopilotClient` is injectable for full SDK access (events, attachments, session
management). `chatClient.GetService<CopilotSession>()` returns the underlying
session inside a call when needed.

### 3.5 Using core with or without Aspire

| Setup | Runtime | Registration |
| --- | --- | --- |
| Single app | Bundled runtime, started by the SDK | `AddCopilot()` |
| Shared runtime | Your own headless runtime | `AddCopilot()` + `Copilot:Endpoint` |
| Aspire AppHost | AppHost resource | `AddCopilot()` reads `ConnectionStrings:copilot`, or `builder.AddCopilotClient("copilot")` from `CopilotSdk.Aspire` for Aspire's health and telemetry defaults |

Application code is the same in all three.

---

## 4. Conversations

- **`ConversationId` set**: the integration resumes that Copilot session and
  sends only the new user message. Concurrent sends to the same conversation are
  queued, not interleaved.
- **`ConversationId` not set**: a new session is created.
  `ChatResponse.ConversationId` returns its id so the caller can continue it.
- **History without a `ConversationId`** (several prior assistant messages) is
  rejected with a clear error. The runtime owns history; replaying it as a
  transcript would hide surprises.
- **Ownership**: store the conversation id on your own entity (a ticket, a
  customer) and authorize access to that entity as usual. Never accept a raw
  conversation id from one user and resume it for another.
- Sessions are disconnected after each call and resumed on the next; history
  stays in the runtime. Warm-session caching is an internal optimization, not
  an API.

---

## 5. Typed results and streaming

These come from `Microsoft.Extensions.AI` and map to SDK features:

```csharp
var triage = await chat.GetResponseAsync<TriageResult>(              // SDK structured output
    $"Classify this ticket. Subject: {t.Subject}. Body: {t.Body}", cancellationToken: ct);

app.MapPost("/support/stream", (IChatClient chat, SupportRequest req, CancellationToken ct) =>
    TypedResults.ServerSentEvents(chat
        .GetStreamingResponseAsync(req.Message, new() { ConversationId = req.ConversationId }, ct)
        .Select(u => u.Text)));
```

Cancelling the token aborts the runtime turn (`session.AbortAsync()`), and so
does `ResponseTimeout`. Client disconnects in ASP.NET Core cancel the turn.

---

## 6. Tools

```csharp
builder.Services.AddCopilot().WithTools<OrderTools>();
```

- **Discovery**: public methods with `[Description]` (the `Microsoft.Extensions.AI`
  convention). Tool names are snake_case method names (`lookup_order`).
- **Registration**: `WithTools<T>()` registers `T` as scoped if it isn't already
  registered.
- **Scope per call**: each tool call resolves `T` from a new DI scope, so
  `DbContext` and other scoped services behave as in a request.
- **Ambient context**: the caller's `ExecutionContext` is restored inside the
  tool, so `IHttpContextAccessor`, `Activity.Current`, logging scopes, and
  culture work as if the tool ran inline. No new context API is needed.
- **Errors**: exceptions are logged and returned to the model as a generic
  failure message, so details don't leak into the conversation.
- `WithTool(delegate)` covers one-off lambdas. Delegate parameters that are
  registered services are injected from the call's scope.

---

## 7. Permissions

The defaults let the app's own tools run, and deny everything else:

| Request | Default |
| --- | --- |
| Tools the app registered | Run without a prompt (`SkipPermission`). |
| Tools marked `[RequiresApproval]` | Sent to `OnApproval`; denied if it isn't set. |
| MCP server tools | Allowed for the tools listed on the server (`Tools`); others denied. |
| Built-in shell, file write, and URL tools | Not offered: client `Mode = Empty` and only app and MCP tools are available. |

```csharp
public sealed class OrderTools(...)
{
    [Description("Cancels an order that has not shipped."), RequiresApproval]
    public Task<CancelResult> CancelOrder(int id, CancellationToken ct) => ...;
}

builder.Services.AddCopilot(o => o.OnApproval = async (request, services, ct) =>
    services.GetRequiredService<IHttpContextAccessor>().HttpContext!.User.IsInRole("Supervisor"));
```

For anything else, set the SDK's `OnPermissionRequest` through
`ConfigureSession`; it replaces the default handler. Approval UIs (queues,
SignalR prompts) are shown in the back-office sample, not built into the
library.

---

## 8. Identity

| Mode | How |
| --- | --- |
| **Service identity** (workers, internal tools) | `Copilot:GitHubToken` from a secret store, or the runtime's own sign-in. |
| **Per-user identity** (usage billed to and scoped by the signed-in user) | `o.GitHubTokenProvider`, called at the start of each turn. |
| **BYOK** (your own model deployment) | `Copilot:Provider`. |

```csharp
builder.Services.AddCopilot(o => o.GitHubTokenProvider = (services, ct) =>
    services.GetRequiredService<IHttpContextAccessor>().HttpContext!.GetTokenAsync("access_token"));
```

If a token provider is set and returns `null`, the call fails instead of falling
back to the service identity.

---

## 9. Runtime behavior

- **Lifecycle**: the client starts on first use (or at host start with
  `EagerStart`) and stops with the host, force-stopping if shutdown times out.
- **Connection**: with no `Endpoint`, the SDK starts its bundled runtime; with an
  `Endpoint`, the client connects to it.
- **Concurrency**: when `MaxConcurrentTurns` is set, extra turns wait in a
  bounded queue and fail when it is full.
- **Retries**: none. A turn that ran tools is not safe to replay. Use
  `Microsoft.Extensions.Resilience` around calls where retries make sense.

---

## 10. Health and observability

```csharp
builder.Services.AddHealthChecks().AddCopilot();          // ping + auth status; never starts a lazy client
```

- **Logs**: the SDK logs through the host's `ILoggerFactory`.
- **Traces and metrics**: `IChatClient` is wrapped with
  `Microsoft.Extensions.AI` OpenTelemetry (GenAI conventions), so
  `AddSource("Experimental.Microsoft.Extensions.AI")` or the standard MEAI
  setup is enough. Tool calls run inside the caller's trace.
- **Runtime telemetry**: when `OTEL_EXPORTER_OTLP_ENDPOINT` is configured, the
  runtime exports its own spans (model calls) to it, correlated by
  `traceparent`.
- Message content is never recorded unless the app opts in.

---

## 11. Testing and other frameworks

- **Testing**: application code depends on `IChatClient`, so tests replace it
  with any fake or mock (`services.AddSingleton<IChatClient>(fake)` in
  `WebApplicationFactory`). Tool classes are plain classes and are tested
  directly.
- **Microsoft Agent Framework**: use `Microsoft.Agents.AI.GitHub.Copilot`
  (`copilotClient.AsAIAgent(...)`) with the injected `CopilotClient`. No extra
  package is needed.

---

## 12. Package and layout

One package: **`CopilotSdk.Extensions.Hosting`** (working name). It depends on
`GitHub.Copilot.SDK`, `Microsoft.Extensions.AI`, and the hosting, options, and
health-check abstractions.

```
dotnet-core/
├── README.md
├── docs/design.md
├── src/CopilotSdk.Extensions.Hosting/
├── samples/                 # each with a raw-SDK version for comparison
│   ├── SupportApi/          # minimal API, conversations, streaming, EF Core tools, per-user tokens
│   ├── InvoiceWorker/       # BackgroundService, GetResponseAsync<T>, MaxConcurrentTurns
│   └── BackOffice/          # Blazor Server, [RequiresApproval] with a SignalR approval UI, MCP
└── tests/
```

---

## 13. Deferred, and why

Each item below adds concepts. It ships only if samples show apps repeatedly
writing the same code without it.

| Idea | Why not now |
| --- | --- |
| Source-generated typed agent interfaces | `GetResponseAsync<T>()` already returns typed results in one line. |
| `AIAgent` registration package | `Microsoft.Agents.AI.GitHub.Copilot` already provides it. |
| ASP.NET Core package (user tokens, endpoints) | `GitHubTokenProvider` is one line; .NET 10 has built-in server-sent events. |
| Approval queue, conversation store, diagnostics endpoint | App-specific; shown in samples. |
| Scripted fake runtime | Faking `IChatClient` covers application tests. Revisit for testing the integration itself. |
| Custom exception types, `ActivitySource`, `Meter` | MEAI telemetry and standard exceptions cover v1. |
| Hooks and event listeners as DI services | Available through `ConfigureSession` and `CopilotClient`. |

---

## 14. Open questions

1. **Package name.** `CopilotSdk.Extensions.Hosting` (the `GitHub.*` and
   `Microsoft.*` prefixes are reserved), or propose upstream to
   `github/copilot-sdk`.
2. **Folder layout.** Separate `dotnet-core/` and `dotnet-aspire/`, or one
   `dotnet/` solution.
3. **Session reuse.** Disconnect after each call (simple, stateless app) vs.
   keep warm sessions with an idle timeout (faster multi-turn chats).
4. **Built-in tools.** Should an app be able to enable the runtime's file and
   shell tools with one option, or only through `ConfigureSession`?

---

## 15. Phasing

| Phase | Scope |
| --- | --- |
| **1** | `AddCopilot`, configuration, lifecycle, `IChatClient` (conversations, typed results, streaming, cancellation), `WithTools<T>`, default permissions, health check, telemetry; `SupportApi` sample with raw-SDK comparison. |
| **2** | Named agents, MCP and BYOK from configuration, per-user tokens, `[RequiresApproval]`, `MaxConcurrentTurns`; `InvoiceWorker` and `BackOffice` samples. |
| **3** | Revisit §13 based on sample feedback; documentation site chapters. |
