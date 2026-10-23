---
title: Chat clients and agents
---

# Chat clients and agents

An agent is an `IChatClient` from
[Microsoft.Extensions.AI](https://learn.microsoft.com/dotnet/ai/microsoft-extensions-ai)
backed by Copilot sessions. Application code depends only on that interface; the integration
maps its calls to the Copilot SDK.

**In this chapter**

- [AddCopilot](#addcopilot)
- [Calling the agent](#calling-the-agent)
- [Per-call ChatOptions](#per-call-chatoptions)
- [Named agents](#named-agents)
- [The CopilotClient escape hatch](#the-copilotclient-escape-hatch)
- [Microsoft Agent Framework](#microsoft-agent-framework)

## AddCopilot

```cs
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

`AddCopilot` registers:

- `IChatClient` (a singleton), wrapped with `Microsoft.Extensions.AI` OpenTelemetry;
- the shared `CopilotClient` and a hosted service that starts and stops it;
- the default [permission handler](permissions.md) and [options validation](configuration.md#validation).

Calling `AddCopilot()` more than once is safe; later calls add to the existing registration.
Every agent shares one `CopilotClient` and one runtime.

## Calling the agent

```cs
public sealed class SupportService(IChatClient chat)
{
    public async Task<string> AskAsync(string question, CancellationToken ct)
    {
        var response = await chat.GetResponseAsync(question, cancellationToken: ct);
        return response.Text;
    }
}
```

Each call is one runtime turn. The `ChatResponse` carries:

| Property | Value |
| --- | --- |
| `Text` | The assistant's reply. |
| `ConversationId` | The Copilot session id; pass it back to continue. See [Conversations](conversations.md). |
| `ModelId` | The model that answered. |
| `Usage` | Token usage summed over the turn (a turn can make several model calls). |
| `RawRepresentation` | The SDK's final assistant message event. |

Typed results and streaming use the same interface:
`GetResponseAsync<T>(...)` and `GetStreamingResponseAsync(...)`. See
[Streaming and structured output](streaming.md).

## Per-call ChatOptions

| `ChatOptions` member | Effect |
| --- | --- |
| `ConversationId` | Resumes that conversation; unset creates a new one. |
| `ModelId` | Overrides the model for this call. |
| `Reasoning.Effort` | Overrides the reasoning effort (`Low`, `Medium`, `High`, `ExtraHigh` map to `low`, `medium`, `high`, `xhigh`). |
| `Instructions` | Appended to the system message of **new** conversations only. |
| `Tools` | `AIFunction` tools added for this call only. They run without a permission prompt (the caller supplied them) and under the caller's `ExecutionContext`. Other `AITool` kinds are ignored. |
| `ResponseFormat` | A JSON schema for [structured output](streaming.md#structured-output). |

System messages in the request are treated like `ChatOptions.Instructions`. User messages
can carry `DataContent` (for example an image), which is sent as an attachment.

## Named agents

A single-agent app never sees a name. When you need more than one agent, name the extra ones
and inject keyed clients:

```cs
builder.Services.AddCopilot(o => o.Instructions = "You help customers.")
    .WithTools<OrderTools>();
builder.Services.AddCopilot("triage", o => o.Instructions = "You triage support tickets.");

public sealed class TicketService([FromKeyedServices("triage")] IChatClient triage) { /* ... */ }
```

- A named agent starts from the default agent's options (code delegates, the `Copilot`
  section, `McpServers`, `ConfigureSession` actions), then applies its own delegates and
  `Copilot:Agents:{name}`.
- Tools are per agent: `WithTools<T>()` on the `"triage"` builder adds tools to `triage` only.
- Client-level settings (`Endpoint`, `GitHubToken`, `MaxConcurrentTurns`, `EagerStart`,
  `ConfigureClient`) come from the default agent.

```json
{
  "Copilot": {
    "Model": "gpt-5.4",
    "Agents": {
      "triage": { "Model": "gpt-5.4-mini" }
    }
  }
}
```

## Fresh-session loops (Ralph loop)

`AddCopilot` also registers a `CopilotLoopRunner` for the agent (keyed by name for named
agents). It sends the same prompt repeatedly, each iteration in a new session (a
`GetResponseAsync` call without a conversation ID). State lives on disk (for example
`IMPLEMENTATION_PLAN.md` in the agent's working directory), and builds or tests are the
quality gate.

```csharp
public sealed class BuildLoop([FromKeyedServices("builder")] CopilotLoopRunner loop) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var result = await loop.RunAsync(new CopilotLoopOptions
        {
            PromptProvider = (_, ct) => new(File.ReadAllTextAsync("PROMPT_build.md", ct)), // re-read every iteration
            MaxIterations = 20,
            IterationTimeout = TimeSpan.FromMinutes(15),
            Check = async (_, ct) => (await RunTestsAsync(ct)) == 0,
            StopWhen = it => it.Text?.Contains("ALL TASKS DONE") == true,
            OnIteration = it => Console.WriteLine($"iteration {it.Number}: {it.Duration}"),
        }, stoppingToken);
    }
}
```

The loop stops at the first of `StopWhen` (after a successful iteration), `Check` returning
`false` (only between iterations), a failed or timed-out iteration (unless
`ContinueOnFailure`), cancellation, or `MaxIterations`; `CopilotLoopResult.StopReason` says
which. `CopilotLoopRunner.RunAsync(step, options)` runs a custom step that opens its own fresh
session.

Each iteration uses the agent's permission policy; nothing is approved implicitly. Run loops
against a disposable checkout inside a container or VM, observe tool use through the existing
telemetry, and host them in a `BackgroundService`, Hangfire, or Quartz.NET job.

## The CopilotClient escape hatch

`CopilotClient` is registered as a singleton, so any part of the SDK is one injection away:

```cs
app.MapGet("/models", (CopilotClient copilot) => copilot.ListModelsAsync());
```

From an `IChatClient`, `GetService<CopilotClient>()` returns the same client. During a call
(for example inside a tool), `chat.GetService<CopilotSession>()` returns the underlying
session, and `GetService<ChatClientMetadata>()` reports the provider name and default model.

To change session or client settings that `CopilotOptions` doesn't cover, use
[`ConfigureSession` and `ConfigureClient`](configuration.md#escape-hatches).

## Microsoft Agent Framework

For agent orchestration patterns, use `Microsoft.Agents.AI.GitHub.Copilot`
(`copilotClient.AsAIAgent(...)`) with the injected `CopilotClient`. No extra package from this
repository is needed.
