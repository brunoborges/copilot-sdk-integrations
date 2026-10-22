---
title: Approvals and permissions
---

# Approvals and permissions

Every tool call and runtime action goes through a permission handler. Without one, the SDK
leaves requests pending and the turn hangs. `AddCopilot` installs a safe default: your own tools
run, tools marked `[RequiresApproval]` go through your `OnApproval` policy, and everything else
is denied.

**In this chapter**

- [The default handler](#the-default-handler)
- [RequiresApproval and OnApproval](#requiresapproval-and-onapproval)
- [The approval request](#the-approval-request)
- [Replacing the handler](#replacing-the-handler)

## The default handler

| Request | Decision |
| --- | --- |
| Tools the agent registered (`WithTools<T>()`, `WithTool(...)`) | Approved. |
| Tools marked `[RequiresApproval]` | Sent to `OnApproval`. Not set, `false`, or an exception → denied. |
| MCP server tools | Approved only if listed in the server's `Tools` (or `"*"`). An empty list allows none. See [MCP servers](mcp-servers.md). |
| Everything else (shell, file writes, URL fetches, …) | Denied. |

Denials are logged at Information level and the model is told the request was denied, so it can
explain instead of failing. Built-in runtime tools are also not offered in the first place
(see [Which tools the model sees](tools.md#which-tools-the-model-sees)); the handler is a second
line of defense. Per-call tools passed in `ChatOptions.Tools` skip the permission prompt,
because the caller supplied them.

## RequiresApproval and OnApproval

Mark a tool method with `[RequiresApproval]` and decide its calls in `OnApproval`:

```cs
using System.ComponentModel;
using CopilotSdk.Extensions.Hosting;

public sealed class OrderTools(IOrderRepository orders)
{
    [Description("Refunds part or all of an order."), RequiresApproval]
    public RefundResult RefundOrder(int orderId, decimal amount) => orders.Refund(orderId, amount);
}

builder.Services.AddCopilot(o => o.OnApproval = call =>
    ValueTask.FromResult(call.GetArgument<decimal>("amount") < 100))
    .WithTools<OrderTools>();
```

In the [`SupportApi` sample](samples.md#supportapi), refunds under $100 are approved and larger
ones are denied; the assistant tells the customer so.

- `OnApproval` is a `Func<CopilotApprovalRequest, ValueTask<bool>>`. Return `true` to approve
  the call once.
- When `OnApproval` is not set, `[RequiresApproval]` tools are always denied.
- If `OnApproval` throws, the error is logged and the call is denied.
- `OnApproval` runs in the caller's `ExecutionContext`, so `IHttpContextAccessor`,
  `Activity.Current`, and logging scopes work, as they do in [tools](tools.md#ambient-context).
- `OnApproval` is per agent; named agents inherit the default agent's policy unless they set
  their own.

Policies can be asynchronous and use services:

```cs
builder.Services.AddCopilot(o => o.OnApproval = async call =>
{
    var limits = call.Services.GetRequiredService<IRefundLimits>();
    var max = await limits.GetMaxRefundAsync(call.CancellationToken);
    return call.ToolName == "refund_order" && call.GetArgument<decimal>("amount") <= max;
});
```

Approval UIs (queues, SignalR prompts, human review) belong in the app; `OnApproval` is where
you plug them in.

## The approval request

| Member | Description |
| --- | --- |
| `ToolName` | The tool name as the model sees it, for example `refund_order`. |
| `Arguments` | The arguments the model supplied, as a `JsonElement` object. |
| `GetArgument<T>(name)` | Reads an argument by name (case-insensitive) and deserializes it with web defaults. Returns `default` when absent. |
| `Services` | The services of the calling turn. |
| `CancellationToken` | Cancelled when the turn is cancelled. |
| `Request` | The underlying SDK `PermissionRequest`. |

## Replacing the handler

For full control, set the SDK's `OnPermissionRequest` through `ConfigureSession`. It runs after
the integration's own settings, so it replaces the default handler:

```cs
builder.Services.AddCopilot(o => o.ConfigureSession(session =>
    session.OnPermissionRequest = (request, invocation) =>
        Task.FromResult(PermissionDecision.Reject("Not allowed."))));
```

When you replace it, `[RequiresApproval]`, `OnApproval`, and the MCP allow-lists no longer apply;
your handler decides every request.
