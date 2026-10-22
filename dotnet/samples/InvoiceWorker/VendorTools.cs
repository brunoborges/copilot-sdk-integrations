using System.ComponentModel;

/// <summary>Tools the invoice agent can call. Resolved from a new DI scope for every call.</summary>
public sealed class VendorTools(IVendorDirectory vendors, ILogger<VendorTools> logger)
{
    [Description("Looks up a vendor by name. Returns its id and payment terms, or null when the vendor is unknown.")]
    public Vendor? LookupVendor([Description("The vendor name as printed on the invoice.")] string name)
    {
        var vendor = vendors.FindByName(name);
        logger.LogInformation("lookup_vendor({Name}) -> {VendorId}", name, vendor?.Id ?? "not found");
        return vendor;
    }
}
