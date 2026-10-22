using GitHub.Copilot;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddScoped<IOrderRepository, InMemoryOrderRepository>();
builder.Services.AddSingleton(sp => new CopilotClient(new CopilotClientOptions
{
    // Empty mode: no built-in shell/file/URL tools and no ambient CLI state. It requires a runtime home directory.
    Mode = CopilotClientMode.Empty,
    BaseDirectory = Directory.CreateDirectory(Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "copilot-sdk", "SupportApi.RawSdk")).FullName,
    GitHubToken = builder.Configuration["Copilot:GitHubToken"], // null: the logged-in user is used
    Logger = sp.GetRequiredService<ILoggerFactory>().CreateLogger("GitHub.Copilot"),
}));
builder.Services.AddHostedService<CopilotLifetime>();
builder.Services.AddSingleton<SupportAgent>();
builder.Services.AddHealthChecks().AddCheck<CopilotHealthCheck>("copilot");

var app = builder.Build();

app.MapPost("/chat", (SupportAgent agent, ChatRequest req, CancellationToken ct) => agent.ChatAsync(req, ct));

app.MapPost("/chat/stream", (SupportAgent agent, ChatRequest req, CancellationToken ct) =>
    TypedResults.ServerSentEvents(agent.StreamAsync(req, ct)));

app.MapPost("/triage", (SupportAgent agent, TriageRequest req, CancellationToken ct) => agent.TriageAsync(req, ct));

app.MapHealthChecks("/health");

app.Run();
