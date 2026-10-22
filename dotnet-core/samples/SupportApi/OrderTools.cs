using System.ComponentModel;
using CopilotSdk.Extensions.Hosting;

/// <summary>Tools the support agent can call. Resolved from a new DI scope for every call.</summary>
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
