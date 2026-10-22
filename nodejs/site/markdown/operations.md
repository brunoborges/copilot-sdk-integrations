---
title: Operations
---

# Operations

Running Copilot agents in production: capacity limits, health checks, the info endpoint,
graceful shutdown, error handling, and observability.

**In this chapter**

- [Runtime lifecycle](#runtime-lifecycle)
- [Runtime restarts](#runtime-restarts)
- [Capacity and limits](#capacity-and-limits)
- [Health](#health)
- [Info endpoint](#info-endpoint)
- [Graceful shutdown](#graceful-shutdown)
- [HTTP error mapping](#http-error-mapping)
- [Errors](#errors)
- [Observability](#observability)
- [Security checklist](#security-checklist)

## Runtime lifecycle

`copilot.state` is one of `not-started`, `starting`, `running`, `stopping`, `stopped`, or
`failed`.

- With `startup: "lazy"` (the default), the runtime starts on the first turn, and a failure
  surfaces on that turn as `CopilotRuntimeUnavailableError`.
- With `startup: "eager"`, NestJS starts it at bootstrap and bootstrap fails if it can't start.
  In core, call `await copilot.start()` at startup.
- A failed start leaves the client `failed`; the next turn or `start()` retries.
- If the runtime process dies or its connection drops while running, the client goes to `failed`
  and the next turn starts a fresh runtime. See [Runtime restarts](#runtime-restarts).

## Runtime restarts

While the runtime is `running`, the integration checks its connection every
`restart.checkIntervalMs` (and whenever a turn fails or `health()` is called). When the connection
is lost:

1. `copilot.state` becomes `failed`, so [health](#health) reports `down` (HTTP `503`).
2. Turns running on the lost runtime end with `CopilotRuntimeUnavailableError` (HTTP `503`).
3. The next turn starts a new runtime process. Persistent conversations resume from the
   runtime's stored session state.

Repeated failures back off: the first restart is immediate, later ones wait `backoffMs`,
doubling up to `maxBackoffMs`. Turns that arrive meanwhile wait for the restart (their
`responseTimeout` still applies). After `maxAttempts` consecutive failed starts, turns fail fast
with `CopilotRuntimeUnavailableError` and automatic restarts stop. An explicit `copilot.start()` always retries immediately and
resets the counters.

```ts
createCopilot({
  restart: { maxAttempts: 5, backoffMs: 1_000, maxBackoffMs: 30_000, checkIntervalMs: 1_000 },
});
```

| Option | Default | Description |
| --- | --- | --- |
| `restart.maxAttempts` | `Infinity` | Consecutive failed starts before automatic restarts stop. `0` disables automatic restarts (only `start()` recovers). |
| `restart.backoffMs` | `1000` | Delay before the second consecutive attempt; doubles after each failure. |
| `restart.maxBackoffMs` | `30000` | Upper bound for the delay. |
| `restart.checkIntervalMs` | `1000` | How often a running runtime's connection is checked. |

## Capacity and limits

| Limit | Default | When exceeded |
| --- | --- | --- |
| `limits.maxConcurrentTurns` | 16 | The turn queues. |
| `limits.maxQueuedTurns` | 64 | `CopilotAdmissionRejectedError` (HTTP `429`, `Retry-After: 1`). |
| `limits.conversationWaitMs` | 30 s | `CopilotConversationBusyError` (HTTP `409`). |
| `responseTimeout` | 5 min | `CopilotTimeoutError` (HTTP `504`). Includes queueing time. |
| `approvalWindow` | 2 min | The `ask-user` permission is denied. |

`copilot.capacity()` returns `{ maxConcurrentTurns, activeTurns, maxQueuedTurns, queuedTurns }`.
Size `maxConcurrentTurns` to what the runtime and your Copilot plan sustain; each active turn
holds a runtime session. See [turn admission](conversations.md#turn-admission).

## Health

`copilot.health()` returns `{ state, version?, capacity }` without starting a lazy runtime.

In NestJS, `CopilotHealthIndicator` plugs into [`@nestjs/terminus`](https://docs.nestjs.com/recipes/terminus)
11 or 12 (an optional peer dependency, loaded only when the indicator runs):

```ts
@Controller("health")
export class HealthController {
  constructor(private readonly health: HealthCheckService, private readonly copilot: CopilotHealthIndicator) {}

  @Get()
  @HealthCheck()
  check() {
    return this.health.check([() => this.copilot.isHealthy("copilot")]);
  }
}
```

`isHealthy(key = "copilot")` resolves to
`{ copilot: { status: "up" | "down", state, version?, capacity } }`. It reports `down` when the
runtime is `stopping`, `stopped`, or `failed`, and `up` for a lazy runtime that hasn't started
yet. The result is built with Terminus's `HealthIndicatorService` (`check(key).up()/down()`), so
`HealthCheckService` answers `503 Service Unavailable` when Copilot is down and `200` otherwise.
The indicator never starts a lazy runtime.

## Info endpoint

`CopilotInfoModule` is opt-in, the counterpart of the Spring Boot Actuator info endpoint. It
exposes a read-only `GET /copilot/info`:

```ts
imports: [CopilotInfoModule.register({ path: "admin/copilot", guards: [AdminGuard] })]
```

The response contains the runtime `state`, `version` (when running), `capacity`, and the
registered agents: name, description, model, reasoning effort, provider name, custom tool
names, MCP server names, and tool filters. Provider keys, MCP headers and env, and instructions
are never included; `includeInstructions: true` adds instructions. Reading it never starts a
lazy runtime. Protect it with `guards`, or inject `CopilotInfoService` and build your own
endpoint with `info()` or `describeAgents()`.

## Graceful shutdown

`copilot.stop({ gracePeriodMs })` stops admitting turns (new turns fail with
`CopilotShuttingDownError`, HTTP `503`), waits up to the grace period for in-flight turns,
aborts the rest, and stops the runtime.

NestJS calls it on application shutdown with `shutdownGracePeriod` (default 30 s). Enable
shutdown hooks so it runs on `SIGTERM`, and keep the grace period below your platform's
termination timeout:

```ts
const app = await NestFactory.create(AppModule);
app.enableShutdownHooks();
```

Turns suspended on a `useChat` approval live in the AI SDK `CopilotApprovalStore`; call
`approvals.clear()` at shutdown to abort them.

## HTTP error mapping

`CopilotExceptionFilter` is registered globally (disable with `exceptionFilter: false`) and maps
core errors to responses with a `{ statusCode, error, message }` body:

| Error | Status |
| --- | --- |
| `CopilotAdmissionRejectedError` | `429 Too Many Requests` with `Retry-After` |
| `CopilotConversationBusyError`, `CopilotConversationExistsError` | `409 Conflict` |
| `CopilotConversationNotFoundError` | `404 Not Found` |
| `CopilotTimeoutError` | `504 Gateway Timeout` |
| `CopilotAbortedError` | `499` (`CLIENT_CLOSED_REQUEST`) |
| `CopilotRuntimeUnavailableError`, `CopilotShuttingDownError` | `503 Service Unavailable` |
| `CopilotSessionError`, `CopilotOutputValidationError` | `502 Bad Gateway` |
| Others (for example `CopilotConfigurationError`) | `500`, with the generic message "Internal server error" |

5xx errors are logged. If the response has already started (a stream), the filter just ends
it. Non-HTTP contexts rethrow. `copilotErrorToHttp(error)` returns `{ status, headers }` for
your own filters.

Without NestJS, add an error middleware that applies the same table:

```ts
import { CopilotAdmissionRejectedError, CopilotIntegrationError, CopilotTimeoutError } from "@copilot-sdk-integrations/core";

app.use((err, req, res, next) => {
  if (!(err instanceof CopilotIntegrationError)) return next(err);
  if (err instanceof CopilotAdmissionRejectedError) res.set("Retry-After", String(err.retryAfterSeconds));
  const status = err instanceof CopilotAdmissionRejectedError ? 429 : err instanceof CopilotTimeoutError ? 504 : 500;
  res.status(status).json({ error: err.name, message: status === 500 ? "Internal server error" : err.message });
});
```

## Errors

All errors extend `CopilotIntegrationError`, exported from `@copilot-sdk-integrations/core`:

| Error | Raised when |
| --- | --- |
| `CopilotConfigurationError` | Invalid options or agent definitions (fail fast at startup when possible). |
| `CopilotAdmissionRejectedError` | The admission queue is full. Has `retryAfterSeconds`. |
| `CopilotConversationBusyError` | Another turn holds the conversation longer than `conversationWaitMs`. |
| `CopilotConversationNotFoundError` | `resume-only` on an unknown conversation. |
| `CopilotConversationExistsError` | `create-only` on an existing conversation. |
| `CopilotTimeoutError` | The turn exceeded its timeout. |
| `CopilotAbortedError` | The turn's signal was aborted (client disconnect, explicit cancel). |
| `CopilotRuntimeUnavailableError` | The runtime could not start or be reached. |
| `CopilotShuttingDownError` | The client is stopping or stopped. |
| `CopilotSessionError` | The runtime reported a session error. Has `errorType`. |
| `CopilotOutputValidationError` | The structured output didn't match the schema. |

## Observability

There is no built-in OpenTelemetry instrumentation yet. Use the hooks the integration provides:

- **Events.** A global `onEvent` listener (or `@OnCopilotEvent`, or `@nestjs/event-emitter`
  re-emission) sees every turn's `turn-start`, `tool-start`, `tool-end`, `usage`, `error`, and
  `done` events together with the turn's `CopilotContext` (principal, conversation, agent,
  attributes). Record usage per principal, tool latency, and error rates from there. See
  [global event listeners](streaming.md#global-event-listeners).
- **Hooks.** `onPreToolUse`/`onPostToolUse` and the other [hooks](governance.md#hooks) see
  tool arguments and results for audit logs.
- **Logs.** Pass a `logger` (core) or rely on Nest's `Logger` (context `Copilot`); set the
  runtime's `logLevel` for its own diagnostics.
- **Trace correlation.** Copy `traceparent` or request ids into the context with
  `context.headers` (NestJS) or `attributes`, then read them in listeners and tools.

## Security checklist

- Agents are **deny by default**: no tool runs without a [permission policy](governance.md#permission-policies)
  that allows it, and built-in tools (shell, file writes, URL fetches) are hidden unless an
  agent sets `builtInTools: true`.
- Set a principal for every authenticated request, so conversations are isolated per user.
- Use [per-user GitHub tokens](context-and-identity.md#per-user-github-tokens) when actions must
  run with the end user's GitHub permissions.
- Keep provider API keys and MCP credentials in configuration or secret stores; the info
  endpoint never exposes them.
- Protect the info endpoint with guards, and don't expose raw `error` events to untrusted
  clients (the AI SDK adapter already sends only safe messages).
