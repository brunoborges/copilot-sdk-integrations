using System.Collections.Concurrent;
using System.ComponentModel;

// Domain and HTTP contracts shared by SupportApi and SupportApi.RawSdk (linked into the latter), so the two
// samples differ only in how they talk to Copilot.

public sealed record Order(int Id, string Customer, string Product, decimal Total, string Status);

public sealed record RefundResult(int OrderId, decimal Amount, string Status);

public interface IOrderRepository
{
    Order? Find(int id);

    RefundResult Refund(int id, decimal amount);
}

public sealed class InMemoryOrderRepository : IOrderRepository
{
    private static readonly ConcurrentDictionary<int, Order> s_orders = new(new Dictionary<int, Order>
    {
        [1001] = new(1001, "Ada", "Mechanical keyboard", 89.00m, "Delivered"),
        [1002] = new(1002, "Grace", "4K monitor", 420.00m, "Shipped"),
        [1003] = new(1003, "Linus", "USB-C dock", 59.90m, "Processing"),
    });

    public Order? Find(int id) => s_orders.GetValueOrDefault(id);

    public RefundResult Refund(int id, decimal amount)
    {
        var order = Find(id) ?? throw new KeyNotFoundException($"Order {id} not found.");
        if (amount <= 0 || amount > order.Total)
        {
            return new RefundResult(id, amount, "Rejected: amount must be between 0 and the order total.");
        }

        s_orders[id] = order with { Status = "Refunded" };
        return new RefundResult(id, amount, "Refunded");
    }
}

public sealed record ChatRequest(string Message, string? ConversationId = null);

public sealed record ChatReply(string Reply, string? ConversationId);

public sealed record TriageRequest(string Subject, string Body);

public sealed record TicketTriage(
    [property: Description("One of: billing, shipping, product, account, other.")] string Category,
    [property: Description("One of: low, medium, high, urgent.")] string Priority,
    [property: Description("One-sentence summary of the customer's problem.")] string Summary);
