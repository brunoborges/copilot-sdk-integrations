var builder = Host.CreateApplicationBuilder(args);

builder.Services.AddSingleton<InvoiceQueue>();
builder.Services.AddSingleton<IVendorDirectory, InMemoryVendorDirectory>();
builder.Services.AddCopilot(o => o.Instructions =
        "You extract data from supplier invoices. Always call lookup_vendor with the vendor name on the invoice, " +
        "and use its vendor id and payment terms to fill in the result.")
    .WithTools<VendorTools>();
builder.Services.AddHostedService<InvoiceProcessor>();

builder.Build().Run();
