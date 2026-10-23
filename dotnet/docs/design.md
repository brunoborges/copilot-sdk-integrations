# Copilot SDK for .NET applications: design

Status: **Preview — implemented, not yet published to NuGet** · Targets: .NET 10 and .NET 8,
`GitHub.Copilot.SDK` 1.0.x, `Microsoft.Extensions.AI` 10.x.

This document describes the integration of the
[GitHub Copilot SDK for .NET](https://github.com/github/copilot-sdk/tree/main/dotnet)
for business applications on the .NET Generic Host: ASP.NET Core APIs, worker
services, and Blazor apps. It matches the code in
[`src/CopilotSdk.Extensions.Hosting`](../src/CopilotSdk.Extensions.Hosting/).

It is independent of Aspire. The [Aspire integration](../../aspire/docs/design.md)
runs the Copilot runtime in an AppHost and builds on this package, the same way
`Aspire.Npgsql` builds on `Npgsql`.

---

## 1. Design rule: simpler than the SDK, or it doesn't ship

The SDK is already easy to call. This integration exists only to remove code
that every business app would otherwise write. Each feature must pass one test:
**does it delete code from the application?** If a feature adds concepts without
deleting code, it belongs in a sample, in documentation, or in the SDK itself.

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
  directly (§3.5).
- Several ways to call an agent. There is one primary surface (`IChatClient`)
  and one escape hatch (`CopilotClient`).
- Orchestrating the runtime for distributed apps (Aspire's job), and agent
  orchestration patterns (use Microsoft Agent Framework, §11).

---

## 2. Side by side

The [`SupportApi`](../samples/SupportApi/) sample answers customers with two
tools (look up and refund an order), across multiple turns, with streaming,
structured output, and a health check:

```csharp
builder.Services.AddScoped<IOrderRepository, InMemoryOrderRepository>();
builder.Services.AddCopilot(o =>
{
    o.Instructions = "You are Contoso's customer support assistant. Use the tools to look up and refund orders.";
    o.OnApproval = call => ValueTask.FromResult(call.GetArgument<decimal>("amount") < 100);
}).WithTools<OrderTools>();
builder.Services.AddHealthChecks().AddCopilot();

app.MapPost("/chat", async (IChatClient chat, ChatRequest req, CancellationToken ct) =>
{
    var response = await chat.GetResponseAsync(req.Message, new() { ConversationId = req.ConversationId }, ct);
    return new ChatReply(response.Text, response.ConversationId);
});

public sealed class OrderTools(IOrderRepository orders)
{
    [Description("Looks up an order by its id.")]
    public Order? LookupOrder(int orderId) => orders.Find(orderId);

    [Description("Refunds part or all of an order."), RequiresApproval]
    public RefundResult RefundOrder(int orderId, decimal amount) => orders.Refund(orderId, amount);
}
```

[`SupportApi.RawSdk`](../samples/SupportApi.RawSdk/) implements the same
endpoints and behavior directly against `GitHub.Copilot.SDK`. Non-blank,
non-comment C# lines, excluding the shared domain code
([details](../samples/README.md)):

| | SupportApi (integration) | SupportApi.RawSdk |
| --- | ---: | ---: |
| `Program.cs` | 24 | 21 |
| Tools (`OrderTools.cs`) | 12 | — |
| `SupportAgent.cs` (sessions, tools, permissions, locking, streaming, structured output) | — | 162 |
| `CopilotInfrastructure.cs` (lifecycle, health check) | — | 35 |
| **Total** | **36** | **218** |

The tool class is a plain class that can be unit-tested without Copilot.

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
    string? Name { get; }                                              // null for the default agent
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

public sealed class TicketService([FromKeyedServices("triage")] IChatClient triage) { /* ... */ }
```

Named agents inherit the default agent's code delegates, MCP servers, and
`ConfigureSession` actions, then override what they set. Tools are per agent.

### 3.3 Configuration

```jsonc
{
  "Copilot": {
    "Model": "gpt-5.4",
    "Instructions": "You help customers with their orders.",
    "ReasoningEffort": null,
    "ResponseTimeout": "00:02:00",
    "MaxConcurrentTurns": null,          // optional cap across the app
    "EagerStart": false,                 // start with the host instead of on first use
    "InProcess": false,                  // host the runtime in this process (experimental)
    "SessionRetention": null,            // delete sessions idle this long, for example "7.00:00:00"

    "GitHubToken": null,                 // service identity for the SDK-managed runtime; use a secret store
    "Endpoint": null,                    // shared runtime; null = SDK-managed runtime
    "ConnectionToken": null,             // required when Endpoint is not loopback
    "ConnectionName": "copilot",         // ConnectionStrings:{name} to read

    "McpServers": { "docs": { "Type": "http", "Url": "https://docs.internal/mcp", "Tools": [ "search" ] } },
    "Provider": null,                    // BYOK, the SDK's ProviderConfig shape

    "Agents": {
      "triage": { "Model": "gpt-5.4-mini", "Instructions": "You triage support tickets." }
    }
  }
}
```

Binding order for an agent: the default agent's code delegates, the `Copilot`
section, then (for a named agent) its own delegates and `Copilot:Agents:{name}`,
then the connection string. Code sets defaults and configuration overrides them,
so operators can change a model without a rebuild. Client-level settings
(`Endpoint`, `ConnectionToken`, `GitHubToken`, `EagerStart`,
`MaxConcurrentTurns`, `InProcess`, `SessionRetention`, `ConfigureClient`) are
read from the default agent only.

- **`Endpoint`** accepts `tcp://host:port`, `http(s)://host:port`, `host:port`,
  or a loopback port number.
- **`McpServers`** mirror the SDK's stdio and HTTP server configs (`Type` is
  `http`/`sse` or `stdio`/`local`, inferred from `Url` or `Command`). `Provider`
  is the SDK's `ProviderConfig`.
- **Connection string** `ConnectionStrings:copilot` (or `ConnectionName`) is
  what an Aspire AppHost provides (§3.6). Keys, case-insensitive: `Endpoint`,
  `ConnectionToken`, `Model`, `Provider`, `ProviderEndpoint`, `ProviderKey`,
  `WireApi`. Unknown keys are logged as a warning and ignored. Values only fill
  settings that are still unset; `Endpoint` and `ConnectionToken` are applied
  together, and only when no `Endpoint` is set, so an explicit endpoint is never
  paired with another runtime's token.

### 3.4 Validation

Options are validated at startup, with messages that name the key to fix:

- a non-loopback `Endpoint` requires `ConnectionToken`;
- `GitHubToken` cannot be combined with `Endpoint` (a shared runtime manages its
  own sign-in);
- `InProcess` cannot be combined with `Endpoint`;
- `Provider` requires `BaseUrl`;
- an HTTP MCP server needs an absolute http(s) `Url`, a stdio server needs a
  `Command`, and unknown `Type` values are rejected;
- `ResponseTimeout`, `MaxConcurrentTurns`, `SessionRetention`, and MCP
  `Timeout` must be positive.

### 3.5 Escape hatches

Anything not covered is set on the SDK types directly, so there is no
translation layer to learn or keep in sync:

```csharp
builder.Services.AddCopilot(o => o
    .ConfigureClient(client => client.BaseDirectory = "/var/copilot")            // CopilotClientOptions
    .ConfigureSession(session => session.ExcludedTools = ["web_fetch"]));        // SessionConfigBase (create and resume)

app.MapGet("/models", (CopilotClient copilot) => copilot.ListModelsAsync());      // the shared client
```

Both run after the integration's own settings, so they can replace them.
`CopilotClient` is injectable for full SDK access (events, attachments, session
management). Inside a call, `chatClient.GetService<CopilotSession>()` returns
the underlying session.

### 3.6 Using core with or without Aspire

| Setup | Runtime | Registration |
| --- | --- | --- |
| Single app | Bundled runtime, started by the SDK | `AddCopilot()` |
| Shared runtime | Your own headless runtime | `AddCopilot()` + `Copilot:Endpoint` |
| Aspire AppHost | AppHost resource | `AddCopilot()` reads `ConnectionStrings:copilot`, or `builder.AddCopilotClient("copilot")` from `CopilotSdk.Aspire` for Aspire's health and telemetry defaults |

Application code is the same in all three.

---

## 4. Conversations

- **`ConversationId` set**: the integration resumes that Copilot session.
  Concurrent sends to the same conversation are queued, not interleaved.
- **`ConversationId` not set**: a new session is created.
  `ChatResponse.ConversationId` returns its id so the caller can continue it.
- **The new turn** is every user message after the last assistant message,
  combined into one prompt (`DataContent` becomes an attachment). Earlier
  history already lives in the runtime.
- **History without a `ConversationId`** (any assistant message) throws
  `InvalidOperationException`. The runtime owns history; replaying it as a
  transcript would hide surprises.
- **System messages and `ChatOptions.Instructions`** are appended to the system
  message of new conversations only. `ChatOptions.ModelId`, `Reasoning`, and
  `AIFunction` tools apply per call.
- **Ownership**: store the conversation id on your own entity (a ticket, a
  customer) and authorize access to that entity as usual. Never accept a raw
  conversation id from one user and resume it for another.
- Sessions are disconnected after each call and resumed on the next; history
  stays in the runtime.
- **`ICopilotSessionStore`** lists, reads, and deletes those sessions.
  `GetMessagesAsync` maps the persisted journal to `ChatMessage`s without
  resuming (resuming would bump the modified time and re-apply configuration).
  `DeleteAsync` takes the conversation lock, so it never deletes mid-turn.
- **`SessionRetention`** turns on a background sweep that deletes sessions not
  modified within the period. It never starts the runtime, and it skips
  conversations whose lock is held. Off by default: deleting history is the
  app's decision.

---

## 5. Typed results, streaming, and cancellation

These come from `Microsoft.Extensions.AI` and map to SDK features:

```csharp
var triage = await chat.GetResponseAsync<TicketTriage>(
    $"Triage this support ticket.\nSubject: {req.Subject}\n\n{req.Body}", cancellationToken: ct);

app.MapPost("/chat/stream", (IChatClient chat, ChatRequest req, CancellationToken ct) =>
    TypedResults.ServerSentEvents(chat
        .GetStreamingResponseAsync(req.Message, new() { ConversationId = req.ConversationId }, ct)
        .Where(update => update.Text.Length > 0)
        .Select(update => update.Text)));
```

- **Structured output**: the JSON schema for `T` is made strict automatically
  (`additionalProperties: false`, every property required), as the runtime's
  strict mode requires.
- **Timeout**: `ResponseTimeout` (default two minutes) throws `TimeoutException`;
  the clock starts after the turn acquires its conversation lock and
  concurrency slot.
- **Cancellation** throws `OperationCanceledException`. Both abort the runtime
  turn. Client disconnects in ASP.NET Core cancel the turn.

---

## 6. Tools

```csharp
builder.Services.AddCopilot()
    .WithTools<OrderTools>()
    .WithTool((int orderId, IOrderRepository orders) => orders.Find(orderId)?.Status,
        name: "get_order_status", description: "Gets the status of an order.");
```

- **Discovery**: public instance and static methods with `[Description]` (the
  `Microsoft.Extensions.AI` convention). Tool names are snake_case method names
  without an `Async` suffix (`LookupOrderAsync` → `lookup_order`);
  `[DisplayName]` overrides the name. Lambdas passed to `WithTool` need a
  `name`. Duplicate names for one agent throw at startup.
- **Registration**: `WithTools<T>()` registers `T` as scoped if it isn't already
  registered.
- **Scope per call**: each tool call resolves `T` from a new DI scope, so
  `DbContext` and other scoped services behave as in a request.
- **Injected parameters** are excluded from the schema the model sees:
  `[FromServices]`, `[FromKeyedServices]`, registered services,
  `CancellationToken`, `IServiceProvider`, and the SDK's `ToolInvocation`.
- **Ambient context**: the caller's `ExecutionContext` is restored inside the
  tool, so `IHttpContextAccessor`, `Activity.Current`, logging scopes, and
  culture work as if the tool ran inline.
- **Errors**: exceptions are logged under the `CopilotSdk.Tools` category, and
  the model sees only `Tool 'lookup_order' failed.`, so details don't leak into
  the conversation.

---

## 7. Permissions

The default handler lets the app's own tools run and denies everything else:

| Request | Decision |
| --- | --- |
| Tools the app registered | Approved. |
| Tools marked `[RequiresApproval]` | Sent to `OnApproval`. Not set, `false`, or an exception → denied. |
| MCP server tools | Approved only if listed in the server's `Tools` (or `"*"`). An empty list allows none. |
| Everything else (shell, file write, URL fetch, …) | Denied. |

The session's `AvailableTools` is limited to `custom:{name}` for each app tool,
plus `mcp:*` when any MCP server lists tools, so built-in tools aren't offered
in the first place.

```csharp
public sealed class OrderTools(IOrderRepository orders)
{
    [Description("Refunds part or all of an order."), RequiresApproval]
    public RefundResult RefundOrder(int orderId, decimal amount) => orders.Refund(orderId, amount);
}

builder.Services.AddCopilot(o => o.OnApproval = call =>
    ValueTask.FromResult(call.GetArgument<decimal>("amount") < 100));
```

`OnApproval` is a `Func<CopilotApprovalRequest, ValueTask<bool>>`. The request
has `ToolName`, `Arguments` (`JsonElement`), `GetArgument<T>(name)`,
`Services` (the calling turn's services), `CancellationToken`, and `Request`
(the raw SDK `PermissionRequest`).

For anything else, set the SDK's `OnPermissionRequest` through
`ConfigureSession`; it replaces the default handler. Approval UIs (queues,
SignalR prompts) belong in the app, not the library.

---

## 8. Runtime and identity

| Mode | How |
| --- | --- |
| **Signed-in user** (local development) | Nothing to configure: the SDK-managed runtime uses the user signed in to the Copilot CLI. |
| **Service identity** (workers, internal tools) | `Copilot:GitHubToken`, or the `COPILOT_GITHUB_TOKEN` / `GH_TOKEN` / `GITHUB_TOKEN` environment variable (SDK-managed runtime only). |
| **Per-user identity** (usage billed to and scoped by the signed-in user) | `o.GitHubTokenProvider`, called at the start of each turn. |
| **BYOK** (your own model deployment) | `Copilot:Provider`. |

```csharp
builder.Services.AddCopilot(o => o.GitHubTokenProvider = (services, ct) =>
    services.GetRequiredService<IHttpContextAccessor>().HttpContext!.GetTokenAsync("access_token"));
```

The provider runs on the caller's thread when the turn starts (so
`HttpContext` is available), and the token is handed to the runtime with a
300-second expiry; refreshes call it again under the caller's context. If it
returns `null`, the call throws `InvalidOperationException` instead of falling
back to the service identity.

**Service token resolution.** For the SDK-managed runtime, the first non-empty
value wins: `Copilot:GitHubToken` → `COPILOT_GITHUB_TOKEN` → `GH_TOKEN` →
`GITHUB_TOKEN` → the signed-in CLI user. The environment variables are read
through `IConfiguration` and passed to the runtime explicitly, because the
SDK-managed runtime does not pick them up on its own. They are ignored when
`Endpoint` is set (a shared runtime manages its own identity).

**Mode and runtime home.** The client runs with `Mode = Empty`: built-in
tools, skills, memory, and custom instructions are off by design. Unless
`Endpoint` or `BaseDirectory` is set, the SDK-managed runtime uses a per-app
home under `LocalApplicationData/copilot-sdk/{app}-{hash}`, so it doesn't mix
with interactive CLI sessions. The signed-in user still works locally.
`ConfigureClient` can override both.

---

## 9. Runtime behavior

- **Lifecycle**: the client starts on first use (or at host start with
  `EagerStart`) and stops with the host, force-stopping if shutdown times out.
- **Connection**: with no `Endpoint`, the SDK starts its bundled runtime as a
  child process, or hosts it in-process with `InProcess` (experimental in the
  SDK; runtime OpenTelemetry export is skipped because the SDK rejects it
  in-process); with an `Endpoint`, the client connects to it.
- **Concurrency**: when `MaxConcurrentTurns` is set, extra turns wait in a
  queue of up to 8× the limit; beyond that the call throws
  `InvalidOperationException`.
- **Retries**: none. A turn that ran tools is not safe to replay. Use
  `Microsoft.Extensions.Resilience` around calls where retries make sense.

---

## 10. Health and observability

```csharp
builder.Services.AddHealthChecks().AddCopilot();
```

| State | Status |
| --- | --- |
| Client not started yet | Healthy (the check never starts it) |
| Ping or auth status fails | The registration's failure status (Unhealthy by default) |
| Not signed in, and no BYOK provider or `GitHubTokenProvider` | Degraded |
| Otherwise | Healthy |

Result data includes `authenticated`, `login`, `authType`, and `host`.

- **Logs**: the SDK logs through the host's `ILoggerFactory` under
  `GitHub.Copilot.CopilotClient`.
- **Traces and metrics**: `IChatClient` is wrapped with `Microsoft.Extensions.AI`
  `UseOpenTelemetry` (GenAI conventions, sensitive data off), so
  `AddSource("Experimental.Microsoft.Extensions.AI")` is enough. Tool calls run
  inside the caller's trace.
- **Runtime telemetry**: when the SDK manages the runtime and
  `OTEL_EXPORTER_OTLP_ENDPOINT` is set, the runtime exports its own spans
  (model calls) over OTLP/HTTP, without message content. A `grpc`
  `OTEL_EXPORTER_OTLP_PROTOCOL` skips it, since the runtime exports HTTP only.
  Edge case: an in-process runtime configured through `ConfigureClient`
  together with the OTLP variables throws when the client starts.

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
dotnet/
├── README.md
├── docs/design.md
├── src/CopilotSdk.Extensions.Hosting/
├── samples/                 # see samples/README.md
│   ├── SupportApi/          # minimal API: conversations, streaming, structured output, approvals, health
│   ├── SupportApi.RawSdk/   # the same app against the raw SDK, for comparison
│   └── InvoiceWorker/       # BackgroundService, GetResponseAsync<T>, a lookup tool
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
| Approval queue, app-side conversation index, diagnostics endpoint | App-specific. `ICopilotSessionStore` covers what the runtime already keeps. |
| Warm-session caching | Sessions are resumed per call; revisit if latency matters. |
| Option to enable built-in runtime tools | Possible through `ConfigureClient` and `ConfigureSession`. |
| Scripted fake runtime | Faking `IChatClient` covers application tests. |
| Custom exception types, `ActivitySource`, `Meter` | MEAI telemetry and standard exceptions cover v1. |
| Hooks and event listeners as DI services | Available through `ConfigureSession` and `CopilotClient`. |

---

## 14. Decisions

1. **Package name.** `CopilotSdk.Extensions.Hosting` (the `GitHub.*` and
   `Microsoft.*` prefixes are reserved).
2. **One runtime per app.** Every agent shares one `CopilotClient` and runtime;
   named agents differ only in session settings. Connecting different agents
   to different runtimes may be revisited if samples need it.

---

## 15. Phasing

| Phase | Scope | Status |
| --- | --- | --- |
| **1** | `AddCopilot`, configuration, lifecycle, `IChatClient` (conversations, typed results, streaming, cancellation), `WithTools<T>`, default permissions, health check, telemetry; `SupportApi` sample with raw-SDK comparison. | ✅ Done |
| **2** | Named agents, MCP and BYOK from configuration, per-user tokens, `[RequiresApproval]`, `MaxConcurrentTurns`; `InvoiceWorker` sample. | ✅ Done; a back-office (Blazor) sample is still to come |
| **3** | Publish to NuGet; revisit §13 based on sample feedback; documentation site chapters. | Planned |
