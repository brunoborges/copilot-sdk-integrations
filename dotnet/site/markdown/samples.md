---
title: Samples
---

# Samples

Runnable samples live in
[`dotnet/samples`](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/dotnet/samples).
Each targets .NET 10 and references the library project directly:

```xml
<ItemGroup>
  <ProjectReference Include="../../src/CopilotSdk.Extensions.Hosting/CopilotSdk.Extensions.Hosting.csproj" />
</ItemGroup>
```

**In this chapter**

- [Overview](#overview)
- [Run the samples](#run-the-samples)
- [SupportApi](#supportapi)
- [SupportApi.RawSdk](#supportapirawsdk)
- [InvoiceWorker](#invoiceworker)

## Overview

| Sample | Kind | What it shows |
| --- | --- | --- |
| `SupportApi` | ASP.NET Core minimal API | `AddCopilot` with instructions, `WithTools<OrderTools>()` (a scoped repository injected per tool call), a `[RequiresApproval]` refund tool decided by `OnApproval`, multi-turn chat through `ChatOptions.ConversationId`, streaming over server-sent events, structured output, and the `/health` check. |
| `SupportApi.RawSdk` | ASP.NET Core minimal API | The same endpoints and behavior written directly against `GitHub.Copilot.SDK`, with no integration, for comparison. |
| `InvoiceWorker` | Worker service | A `BackgroundService` that drains an in-memory invoice queue, extracts an `InvoiceExtraction` record from each invoice, and calls a `lookup_vendor` tool. |

## Run the samples

Locally, the samples use the user signed in to the Copilot CLI (or `gh`). Elsewhere, set a
token as described in [Runtime and identity](identity.md#service-token-resolution). The samples
use the runtime's default model; set `Copilot__Model` to pick one.

From `dotnet/`:

```bash
dotnet run --project samples/SupportApi            # http://localhost:5180
dotnet run --project samples/SupportApi.RawSdk     # http://localhost:5181
dotnet run --project samples/InvoiceWorker         # processes three invoices, then exits
```

## SupportApi

```cs
builder.Services.AddScoped<IOrderRepository, InMemoryOrderRepository>();
builder.Services.AddCopilot(o =>
{
    o.Instructions = "You are Contoso's customer support assistant. Use the tools to look up and refund orders. Keep answers short.";

    // refund_order is marked [RequiresApproval]: refunds under $100 are approved automatically, the rest are denied.
    o.OnApproval = call => ValueTask.FromResult(call.GetArgument<decimal>("amount") < 100);
}).WithTools<OrderTools>();
builder.Services.AddHealthChecks().AddCopilot();
```

Send requests with `SupportApi.http`, or with curl:

```bash
curl localhost:5180/health
curl -H 'content-type: application/json' -d '{"message":"What is the status of order 1002?"}' localhost:5180/chat
# => {"reply":"Order 1002 (4K monitor, $420.00) has shipped.","conversationId":"7ada513d-..."}
curl -H 'content-type: application/json' -d '{"message":"Which product was it?","conversationId":"7ada513d-..."}' localhost:5180/chat
curl -N -H 'content-type: application/json' -d '{"message":"What can you help me with?"}' localhost:5180/chat/stream
curl -H 'content-type: application/json' -d '{"subject":"Charged twice","body":"I was billed twice for order 1003."}' localhost:5180/triage
# => {"category":"billing","priority":"high","summary":"..."}
```

| Endpoint | Shows |
| --- | --- |
| `POST /chat` | [Conversations](conversations.md) with `ChatOptions.ConversationId`. |
| `POST /chat/stream` | [Server-sent events](streaming.md#server-sent-events). |
| `POST /triage` | [Structured output](streaming.md#structured-output) with `GetResponseAsync<TicketTriage>`. |
| `GET /health` | The [health check](observability.md#health-checks). |

Refunds under $100 are approved; larger ones are denied, and the assistant says so.

## SupportApi.RawSdk

The same endpoints and behavior against the raw SDK: built-in runtime tools are off, tools run
in a new DI scope per call, refunds go through an approval policy, concurrent requests to one
conversation are serialized, a timeout aborts the turn, and `/health` checks the runtime and
its sign-in. Both samples share the domain code (`Orders.cs`).

Non-blank, non-comment C# lines, excluding the shared `Orders.cs`:

| | SupportApi (integration) | SupportApi.RawSdk |
| --- | ---: | ---: |
| `Program.cs` | 24 | 21 |
| Tools (`OrderTools.cs`) | 12 | — |
| `SupportAgent.cs` (sessions, tools, permissions, locking, streaming, structured output) | — | 162 |
| `CopilotInfrastructure.cs` (lifecycle, health check) | — | 35 |
| **Total** | **36** | **218** |

What the raw version writes by hand: the `CopilotClient` registration with its mode and runtime
home, a hosted service that stops (or force-stops) the runtime, create-versus-resume with the
session config repeated for both, tool definitions that open a DI scope and hide exception
details, a permission handler, a lock per conversation, aborting the turn on timeout or
cancellation, an event loop for streaming, and the health check. The integration also restores
the caller's `ExecutionContext` inside tools, reports token usage, and emits OpenTelemetry,
which the raw sample omits.

## InvoiceWorker

A worker service with no web stack:

```cs
var builder = Host.CreateApplicationBuilder(args);

builder.Services.AddSingleton<InvoiceQueue>();
builder.Services.AddSingleton<IVendorDirectory, InMemoryVendorDirectory>();
builder.Services.AddCopilot(o => o.Instructions =
        "You extract data from supplier invoices. Always call lookup_vendor with the vendor name on the invoice, " +
        "and use its vendor id and payment terms to fill in the result.")
    .WithTools<VendorTools>();
builder.Services.AddHostedService<InvoiceProcessor>();

builder.Build().Run();
```

The `BackgroundService` calls `GetResponseAsync<InvoiceExtraction>` for each invoice, logs the
result, and stops the application once the queue is drained:

```cs
var response = await chat.GetResponseAsync<InvoiceExtraction>(
    $"Extract the fields of this invoice:\n\n{invoice.Text}", cancellationToken: stoppingToken);
var extraction = response.Result;
```

`VendorTools.LookupVendor` becomes the `lookup_vendor` tool, and takes `IVendorDirectory` and `ILogger<VendorTools>`
through its constructor like any DI service.
