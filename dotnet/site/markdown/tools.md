---
title: Tools
---

# Tools

Tools are .NET methods the model can call during a turn. The Copilot runtime runs the tool
loop; the integration turns your methods into SDK tools, resolves them from DI per call,
restores the caller's context inside them, and keeps exception details out of the conversation.

**In this chapter**

- [Tool classes](#tool-classes)
- [Delegate tools](#delegate-tools)
- [Tool names](#tool-names)
- [Parameters and injected services](#parameters-and-injected-services)
- [A DI scope per call](#a-di-scope-per-call)
- [Ambient context](#ambient-context)
- [Errors](#errors)
- [Which tools the model sees](#which-tools-the-model-sees)

## Tool classes

```cs
using System.ComponentModel;
using CopilotSdk.Extensions.Hosting;

public sealed class OrderTools(IOrderRepository orders)
{
    [Description("Looks up an order by its id.")]
    public Order? LookupOrder([Description("The order id, for example 1001.")] int orderId) => orders.Find(orderId);

    [Description("Refunds part or all of an order.")]
    [RequiresApproval]
    public RefundResult RefundOrder(
        [Description("The order id.")] int orderId,
        [Description("The amount to refund, in USD.")] decimal amount) => orders.Refund(orderId, amount);
}

builder.Services.AddCopilot().WithTools<OrderTools>();
```

- **Discovery** follows the `Microsoft.Extensions.AI` convention: public instance and static
  methods with `[Description]` become tools. Other methods are ignored.
- **Registration**: `WithTools<T>()` registers `T` as scoped if it isn't already registered.
  Register it yourself first to choose another lifetime.
- **Per agent**: tools belong to the agent whose builder added them. See
  [Named agents](chat-clients.md#named-agents).
- `[RequiresApproval]` sends the call to `OnApproval` first. See
  [Approvals and permissions](permissions.md).

A tool class is a plain class, so it can be unit-tested without Copilot.

## Delegate tools

For one-off tools, pass a delegate. Its parameters that are registered services are injected
from the call's scope:

```cs
builder.Services.AddCopilot()
    .WithTools<OrderTools>()
    .WithTool((int orderId, IOrderRepository orders) => orders.Find(orderId)?.Status,
        name: "get_order_status", description: "Gets the status of an order.");
```

Lambdas have no usable method name, so pass `name`. Local functions and methods default to
their snake_case name. `[RequiresApproval]` works on a delegate's method too, for example on a
lambda: `[RequiresApproval] (int orderId) => ...`.

## Tool names

| Method | Tool name |
| --- | --- |
| `LookupOrder` | `lookup_order` |
| `LookupOrderAsync` | `lookup_order` (the `Async` suffix is dropped) |
| `[DisplayName("find_order")] LookupOrder` | `find_order` |

Tool names must be unique within one agent. A duplicate throws `InvalidOperationException` when
the agent's tools are first built, on its first call.

## Parameters and injected services

The model sees a JSON schema built from the method's parameters, with `[Description]` on a
parameter becoming its description. These parameters are excluded from the schema and supplied
by the integration instead:

| Parameter | Supplied value |
| --- | --- |
| `[FromServices]` (ASP.NET Core's attribute) or `[FromKeyedServices(key)]` | The service, from the call's scope. |
| Any type registered in DI | The service, from the call's scope. |
| `CancellationToken` | Cancelled when the turn is cancelled or times out. |
| `IServiceProvider` | The call's scoped service provider. |
| `GitHub.Copilot.ToolInvocation` | The SDK's invocation (tool call id, session id). |

Return values are serialized as the tool result; `Task<T>` and `ValueTask<T>` are awaited.

## A DI scope per call

Each tool call opens a new DI scope and resolves the tool class from it, so `DbContext` and
other scoped services get a fresh instance per call and are disposed when the call ends. To
reach request state (for example the signed-in user), use `IHttpContextAccessor`, which works
inside tools because of the [ambient context](#ambient-context).

## Ambient context

Tool callbacks arrive on the SDK's RPC thread. The integration restores the caller's
`ExecutionContext` inside the tool, so these work as if the tool ran inline in your endpoint:

- `IHttpContextAccessor.HttpContext` and `HttpContext.User`;
- `Activity.Current`, so tool calls appear in the caller's trace;
- logging scopes and `AsyncLocal` values;
- the current culture.

## Errors

Exceptions thrown by a tool are logged under the `CopilotSdk.Tools` category, with the tool
call id and session id. The model receives only `Tool 'lookup_order' failed.`, so exception
details never leak into the conversation, and the model can recover or explain.

## Which tools the model sees

The client runs in the SDK's `Empty` mode, and each session's `AvailableTools` lists
`custom:{name}` for each of the agent's tools, plus `mcp:*` when any
[MCP server](mcp-servers.md) lists tools. Built-in runtime tools (shell, file edits, URL fetch)
are not offered at all. Per-call tools passed in `ChatOptions.Tools` are added for that call
only. To change the list, use
[`ConfigureSession`](configuration.md#escape-hatches), for example
`session.ExcludedTools = ["web_fetch"]`.
