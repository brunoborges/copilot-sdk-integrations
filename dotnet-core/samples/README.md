# Samples

Runnable samples for `CopilotSdk.Extensions.Hosting`. Each one targets .NET 10 and references the library
project directly.

| Sample | Kind | What it shows |
| --- | --- | --- |
| [`SupportApi`](SupportApi/) | ASP.NET Core minimal API | `AddCopilot` with instructions, `WithTools<OrderTools>()` (a scoped repository injected per tool call), a `[RequiresApproval]` refund tool decided by `OnApproval`, multi-turn chat through `ChatOptions.ConversationId`, streaming over server-sent events, structured output, and the `/health` check. |
| [`SupportApi.RawSdk`](SupportApi.RawSdk/) | ASP.NET Core minimal API | The same endpoints and behavior written directly against `GitHub.Copilot.SDK`, with no integration, for comparison. |
| [`InvoiceWorker`](InvoiceWorker/) | Worker service | A `BackgroundService` that drains an in-memory invoice queue, extracts an `InvoiceExtraction` record from each invoice, and calls a `lookup_vendor` tool. |

## Prerequisites

- The .NET 10 SDK.
- A GitHub account with Copilot access. Locally, the samples use the user who is signed in to the
  [Copilot CLI](https://docs.github.com/copilot/how-tos/set-up/install-copilot-cli) (or `gh`); nothing to configure.
  Elsewhere, set a token as `Copilot:GitHubToken`, for example with an environment variable:

  ```bash
  export Copilot__GitHubToken=<token>   # or: dotnet user-secrets set Copilot:GitHubToken <token>
  ```

  The SDK starts its bundled Copilot runtime (downloaded on first build), so no separate install is needed.

The samples use the runtime's default model. To pick one, set `Copilot:Model` (for example
`Copilot__Model=claude-haiku-4.5`); the available models depend on your Copilot plan.

## Run

From `dotnet-core/`:

```bash
dotnet run --project samples/SupportApi            # http://localhost:5180
dotnet run --project samples/SupportApi.RawSdk     # http://localhost:5181
dotnet run --project samples/InvoiceWorker         # processes three invoices, then exits
```

Send requests with [`SupportApi.http`](SupportApi/SupportApi.http) (or
[`SupportApi.RawSdk.http`](SupportApi.RawSdk/SupportApi.RawSdk.http)), or with curl:

```bash
curl localhost:5180/health
curl -H 'content-type: application/json' -d '{"message":"What is the status of order 1002?"}' localhost:5180/chat
# => {"reply":"Order 1002 (4K monitor, $420.00) has shipped.","conversationId":"7ada513d-..."}
curl -H 'content-type: application/json' -d '{"message":"Which product was it?","conversationId":"7ada513d-..."}' localhost:5180/chat
curl -N -H 'content-type: application/json' -d '{"message":"What can you help me with?"}' localhost:5180/chat/stream
curl -H 'content-type: application/json' -d '{"subject":"Charged twice","body":"I was billed twice for order 1003."}' localhost:5180/triage
# => {"category":"billing","priority":"high","summary":"..."}
```

Refunds under $100 are approved; larger ones are denied, and the assistant says so.

## SupportApi vs SupportApi.RawSdk

Both samples share the domain and HTTP contracts ([`Orders.cs`](SupportApi/Orders.cs), linked into the raw
sample), expose the same endpoints, and behave the same: built-in runtime tools are off, the app's tools run in a
new DI scope per call, refunds go through an approval policy, concurrent requests to one conversation are
serialized, a timeout aborts the turn, and `/health` checks the runtime and its sign-in.

Non-blank, non-comment C# lines, excluding the shared `Orders.cs`:

| | SupportApi (integration) | SupportApi.RawSdk |
| --- | ---: | ---: |
| `Program.cs` | 24 | 21 |
| Tools (`OrderTools.cs`) | 12 | — |
| `SupportAgent.cs` (sessions, tools, permissions, locking, streaming, structured output) | — | 162 |
| `CopilotInfrastructure.cs` (lifecycle, health check) | — | 35 |
| **Total** | **36** | **218** |

What the raw version has to write by hand: the `CopilotClient` registration with its mode and runtime home, a hosted
service that stops (or force-stops) the runtime, create-versus-resume with the session config repeated for both,
tool definitions that open a DI scope and hide exception details, a permission handler, a lock per conversation,
aborting the turn on timeout or cancellation, an event loop for streaming, and the health check. The integration
also restores the caller's `ExecutionContext` inside tools, reports token usage, and emits OpenTelemetry, which the
raw sample omits.
