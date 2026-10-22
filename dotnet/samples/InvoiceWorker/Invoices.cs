using System.ComponentModel;
using System.Threading.Channels;

public sealed record Invoice(string FileName, string Text);

public sealed record InvoiceExtraction(
    [property: Description("The vendor id from lookup_vendor, or null when the vendor is unknown.")] string? VendorId,
    string VendorName,
    string InvoiceNumber,
    DateOnly InvoiceDate,
    decimal Total,
    [property: Description("ISO 4217 currency code.")] string Currency,
    [property: Description("The invoice date plus the vendor's payment terms.")] DateOnly? DueDate);

public sealed record Vendor(string Id, string Name, int PaymentTermsDays);

/// <summary>A stand-in for a real queue (Service Bus, SQS, ...), seeded with a few invoices.</summary>
public sealed class InvoiceQueue
{
    private readonly Channel<Invoice> _channel = Channel.CreateUnbounded<Invoice>();

    public InvoiceQueue()
    {
        _channel.Writer.TryWrite(new("inv-001.txt", """
            NORTHWIND TRADERS - INVOICE #NW-2026-0042
            Date: 2026-10-01
            2 x Espresso machine @ 450.00 EUR
            Total due: 900.00 EUR
            """));
        _channel.Writer.TryWrite(new("inv-002.txt", """
            Fabrikam, Inc.
            Invoice no. F-77812, issued October 12, 2026
            Consulting services, 12 hours ........ $1,800.00
            Amount payable: USD 1,800.00
            """));
        _channel.Writer.TryWrite(new("inv-003.txt", """
            Bill from: Tailspin Toys | Ref TT/993 | 15 Oct 2026
            Assorted toys, 1 crate - GBP 320.50
            """));
        _channel.Writer.Complete();
    }

    public IAsyncEnumerable<Invoice> ReadAllAsync(CancellationToken cancellationToken) => _channel.Reader.ReadAllAsync(cancellationToken);
}

public interface IVendorDirectory
{
    Vendor? FindByName(string name);
}

public sealed class InMemoryVendorDirectory : IVendorDirectory
{
    private static readonly Vendor[] s_vendors =
    [
        new("V-100", "Northwind Traders", 30),
        new("V-200", "Fabrikam, Inc.", 45),
    ];

    public Vendor? FindByName(string name) =>
        s_vendors.FirstOrDefault(v => v.Name.Contains(name, StringComparison.OrdinalIgnoreCase) || name.Contains(v.Name, StringComparison.OrdinalIgnoreCase));
}
