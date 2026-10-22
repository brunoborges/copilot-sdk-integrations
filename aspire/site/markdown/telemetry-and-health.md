---
title: Telemetry and health
---

# Telemetry and health

## Runtime telemetry

`AddCopilot` configures the runtime to export OpenTelemetry over OTLP/HTTP (protobuf) to the Aspire
dashboard. When Aspire supplies an OTLP endpoint, the AppHost also sets `COPILOT_OTEL_ENABLED=true`,
which turns the runtime's exporter on. In publish mode, or when there is no dashboard, it stays off.

On the dashboard you see the runtime's traces (Copilot turns, model calls, and tool callbacks), its
metrics, and its structured logs, under the `copilot` resource.

### Message content

By default the runtime doesn't record prompts and responses in telemetry. The AppHost sets
`OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT=false`, overriding Aspire's development default.
Turn capture on for local debugging:

```cs
builder.AddCopilot("copilot").WithMessageContentCapture();
```

Captured content can include user data and tool results. Don't enable it where that data must not
reach your telemetry backend.

## End-to-end traces

A trace connects when the service propagates its trace context to the runtime:

```text
service request ─► Copilot turn (runtime) ─► model call
                                         └─► tool callback ─► service tool code
```

| Language | How trace context reaches the runtime |
| --- | --- |
| .NET | `AddCopilotClient` registers the `Experimental.Microsoft.Extensions.AI` trace source and meter. See [.NET services](dotnet-services.md). |
| Java | See the observability pages of the [Spring Boot](https://brunoborges.github.io/copilot-sdk-integrations/spring-boot/) and [Quarkus](https://brunoborges.github.io/copilot-sdk-integrations/quarkus/) integrations. |
| Node.js | The Node.js SDK takes an `onGetTraceContext` callback that returns the current W3C trace context, for example from `@opentelemetry/api`'s `propagation.inject`. |
| Python | The Python SDK propagates W3C trace context when installed with the `telemetry` extra. See the SDK README. |

Each service exports its own telemetry with its language's OpenTelemetry SDK, using the `OTEL_*`
variables that Aspire injects.

## AppHost health check

`AddCopilot` registers the health check `{name}_copilot_check` on the runtime resource, with a
10-second timeout. It is healthy when:

1. the runtime endpoint is allocated,
2. the runtime answers, and
3. the runtime is signed in to GitHub, **or** every model of the runtime uses a
   [BYOK provider](models.md#bring-your-own-key-providers).

Until then the dashboard shows the resource as unhealthy, with a reason. If the runtime answers but
isn't signed in, the message tells you to sign in with `copilot login` or `gh auth login`, or to set
a token with `WithGitHubToken(...)`.

Use `WaitFor` so that services start only once the runtime can serve prompts:

```cs
builder.AddNodeApp("web", "../web", "server.js").WithReference(chat).WaitFor(chat);
```

## Service health checks

| Language | Health check |
| --- | --- |
| .NET | `AddCopilotClient` registers `copilot_{connectionName}` with the `ready` tag. Disable it with `DisableHealthChecks`. |
| Spring Boot | The starter's health indicator. For a BYOK-only runtime, set `copilot.health.require-authentication=false`. |
| Quarkus | The `copilot` readiness check, when SmallRye Health is present (`quarkus.copilot.health.enabled`). |
| Node.js | `copilot.health()` in core reports the client state; NestJS adds `CopilotHealthIndicator` for `@nestjs/terminus`. |
| Python | None built in. Expose your own endpoint. |
