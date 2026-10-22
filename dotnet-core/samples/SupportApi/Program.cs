using Microsoft.Extensions.AI;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddScoped<IOrderRepository, InMemoryOrderRepository>();
builder.Services.AddCopilot(o =>
{
    o.Instructions = "You are Contoso's customer support assistant. Use the tools to look up and refund orders. Keep answers short.";

    // refund_order is marked [RequiresApproval]: refunds under $100 are approved automatically, the rest are denied.
    o.OnApproval = call => ValueTask.FromResult(call.GetArgument<decimal>("amount") < 100);
}).WithTools<OrderTools>();
builder.Services.AddHealthChecks().AddCopilot();

var app = builder.Build();

app.MapPost("/chat", async (IChatClient chat, ChatRequest req, CancellationToken ct) =>
{
    var response = await chat.GetResponseAsync(req.Message, new() { ConversationId = req.ConversationId }, ct);
    return new ChatReply(response.Text, response.ConversationId);
});

app.MapPost("/chat/stream", (IChatClient chat, ChatRequest req, CancellationToken ct) =>
    TypedResults.ServerSentEvents(chat
        .GetStreamingResponseAsync(req.Message, new() { ConversationId = req.ConversationId }, ct)
        .Where(update => update.Text.Length > 0)
        .Select(update => update.Text)));

app.MapPost("/triage", async (IChatClient chat, TriageRequest req, CancellationToken ct) =>
    (await chat.GetResponseAsync<TicketTriage>($"Triage this support ticket.\nSubject: {req.Subject}\n\n{req.Body}", cancellationToken: ct)).Result);

app.MapHealthChecks("/health");

app.Run();
