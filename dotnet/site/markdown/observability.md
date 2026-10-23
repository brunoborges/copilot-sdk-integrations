---
title: Health checks and telemetry
---

# Health checks and telemetry

The integration plugs into the standard .NET operations stack: ASP.NET Core health checks,
`ILogger`, and OpenTelemetry through `Microsoft.Extensions.AI`.

**In this chapter**

- [Health checks](#health-checks)
- [Logging](#logging)
- [Traces and metrics](#traces-and-metrics)
- [Runtime telemetry](#runtime-telemetry)

## Health checks

```cs
builder.Services.AddHealthChecks().AddCopilot();

app.MapHealthChecks("/health");
```

`AddCopilot` on `IHealthChecksBuilder` pings the shared runtime and checks its auth status. It
never starts a client that hasn't been used yet.

| State | Status |
| --- | --- |
| Client not started yet | Healthy (`Copilot client not started.`) |
| Ping or auth status fails | The registration's failure status (Unhealthy by default) |
| Not signed in, and no BYOK `Provider` or `GitHubTokenProvider` on the default agent | Degraded |
| Not signed in, but a BYOK `Provider` or `GitHubTokenProvider` is configured | Healthy |
| Signed in | Healthy |

The result data includes `authenticated`, and, when the runtime reports them, `login`,
`authType`, and `host`.

```cs
public static IHealthChecksBuilder AddCopilot(
    this IHealthChecksBuilder builder,
    string name = "copilot",
    HealthStatus? failureStatus = null,
    IEnumerable<string>? tags = null,
    TimeSpan? timeout = null);
```

Use `tags` to separate liveness and readiness probes, for example
`AddCopilot(tags: ["ready"])` with `MapHealthChecks("/ready", new() { Predicate = c => c.Tags.Contains("ready") })`.

## Logging

| Category | What it logs |
| --- | --- |
| `GitHub.Copilot.CopilotClient` | The SDK's own logs, through the host's `ILoggerFactory`. |
| `CopilotSdk.Extensions.Hosting.CopilotChatClient` | Sessions created and resumed, per agent. |
| `CopilotSdk.Extensions.Hosting.CopilotClientProvider` | Client start and stop, including force-stops on shutdown. |
| `CopilotSdk.Tools` | Tool failures, with the exception, tool call id, and session id. |
| `CopilotSdk.Permissions` | Denied permission requests (Information) and `OnApproval` failures. |
| `CopilotSdk.Extensions.Hosting.CopilotOptions` | Unknown keys in the [connection string](configuration.md#connection-strings) (Warning). |

## Traces and metrics

Every registered `IChatClient` is wrapped with `Microsoft.Extensions.AI`'s `UseOpenTelemetry`,
which follows the OpenTelemetry GenAI semantic conventions with sensitive data (message
content) off. Add its source and meter:

```cs
builder.Services.AddOpenTelemetry()
    .WithTracing(t => t.AddSource("Experimental.Microsoft.Extensions.AI"))
    .WithMetrics(m => m.AddMeter("Experimental.Microsoft.Extensions.AI"));
```

Tool calls run inside the caller's `ExecutionContext`, so spans they create, such as database
or HTTP calls, appear in the caller's trace. Token usage is reported on `ChatResponse.Usage`
and recorded by the OpenTelemetry wrapper.

## Runtime telemetry

When the SDK manages the runtime (no `Endpoint`) and `OTEL_EXPORTER_OTLP_ENDPOINT` is set, the
runtime exports its own spans (model calls) over OTLP/HTTP, without message content.

- `OTEL_EXPORTER_OTLP_PROTOCOL` values `http/protobuf` and `http/json` are passed through.
- A `grpc` protocol skips runtime export, because the runtime exports over HTTP only.
- A shared runtime (`Endpoint`) owns its own telemetry configuration.

With Aspire, the service defaults set these variables, and `CopilotSdk.Aspire` adds the
telemetry wiring above; see the
[Copilot for Aspire](https://brunoborges.github.io/copilot-sdk-integrations/aspire/)
documentation.
