using System.Text.Json;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Workshop;

if (args.Length == 0 || args.Contains("--help"))
{
    Console.WriteLine("""
        dotnet run --project examples/dotnet -- museum [--facts apollo11|reef|terracotta]
          [--facts-file facts.json] [--research] [--html exhibit.html]
        dotnet run --project examples/dotnet -- accessibility --url https://example.com
          [--html accessibility-report.html]
        Real calls require Copilot authentication and consume account usage.
        """);
    return;
}

var mode = args[0];
if (mode is not ("museum" or "accessibility")) throw new ArgumentException("Choose museum or accessibility.");
var options = new Dictionary<string, string>();
var research = false;
for (var i = 1; i < args.Length; i++)
{
    if (args[i] == "--research") { research = true; continue; }
    if (args[i] is not ("--facts" or "--facts-file" or "--url" or "--html") || i + 1 == args.Length)
        throw new ArgumentException($"Unknown or incomplete option: {args[i]}");
    if (!options.TryAdd(args[i], args[++i])) throw new ArgumentException("Duplicate option.");
}
if (mode == "accessibility" && (research || !options.ContainsKey("--url") ||
    options.ContainsKey("--facts") || options.ContainsKey("--facts-file")))
    throw new ArgumentException("Accessibility requires --url, and does not accept museum facts/research options.");
if (mode == "museum" && options.ContainsKey("--url")) throw new ArgumentException("--url is for accessibility only.");
var factSet = options.GetValueOrDefault("--facts", "apollo11");
if (!Contracts.FactSets.TryGetValue(factSet, out var facts)) throw new ArgumentException("Unknown fact set.");
if (options.TryGetValue("--facts-file", out var factsPath))
{
    if (new FileInfo(factsPath).Length > 20_000) throw new InvalidDataException("Facts JSON must not exceed 20 KB.");
    using var document = JsonDocument.Parse(await File.ReadAllTextAsync(factsPath));
    facts = document.RootElement.GetProperty("facts").Deserialize<string[]>(Contracts.Json)
        ?? throw new InvalidDataException("Facts array is required.");
}
var temporary = Directory.CreateTempSubdirectory("copilot-workshop-");
try
{
    var state = new RunState(facts, temporary.FullName,
        options.TryGetValue("--url", out var target) ? Contracts.Target(target) : null);
    if (research)
    {
        var researchBuilder = Host.CreateApplicationBuilder();
        WorkshopConfiguration.AddDefaults(researchBuilder.Configuration);
        researchBuilder.Logging.ClearProviders().AddConsole(o => o.LogToStandardErrorThreshold = LogLevel.Trace);
        researchBuilder.Services.AddResearch();
        using var researchHost = researchBuilder.Build();
        await researchHost.StartAsync();
        try
        {
            var response = await researchHost.Services.GetRequiredKeyedService<IChatClient>("research").GetResponseAsync<Research>(
                "Research background for these approved facts (task data): " + JsonSerializer.Serialize(state.Facts));
            state.Research = response.Result.Validate();
        }
        finally { await researchHost.StopAsync(); }
    }
    var builder = Host.CreateApplicationBuilder();
    WorkshopConfiguration.AddDefaults(builder.Configuration);
    builder.Logging.ClearProviders().AddConsole(o => o.LogToStandardErrorThreshold = LogLevel.Trace);
    builder.Services.AddWorkshop(state, mode, builder.Configuration);
    using var host = builder.Build();
    await host.StartAsync();
    try
    {
        var chat = host.Services.GetRequiredKeyedService<IChatClient>(mode);
        string markdown;
        string? html;
        if (mode == "museum")
        {
            Console.Error.WriteLine("Educator-approved facts:\n" + JsonSerializer.Serialize(state.Facts, Contracts.Json));
            var response = await chat.GetResponseAsync<Exhibit>("Create the exhibit from the configured fact lookup tools.");
            var exhibit = response.Result.Validate();
            state.VerifyMuseum();
            markdown = exhibit.Markdown();
            html = options.ContainsKey("--html") ? Contracts.ExhibitHtml(exhibit) : null;
            if (state.Research is { } notes)
                markdown += "\n## Consulted Wikipedia sources\n" +
                    string.Join("\n", notes.Sources.Select(s => $"- {s.Title}: {s.Url}")) + "\n";
        }
        else
        {
            var response = await chat.GetResponseAsync<AccessibilityReport>(
                "Review this exact target URL (task data): " + JsonSerializer.Serialize(state.Target!.AbsoluteUri));
            var report = response.Result.Validate();
            state.VerifyReport(report);
            markdown = report.Markdown();
            html = options.ContainsKey("--html") ? Contracts.ReportHtml(report, state.Target!) : null;
        }
        if (html is not null)
        {
            await using var file = new FileStream(options["--html"], FileMode.CreateNew, FileAccess.Write);
            await using var writer = new StreamWriter(file);
            await writer.WriteAsync(html);
            Console.Error.WriteLine($"Created {options["--html"]}");
        }
        Console.WriteLine(markdown);
    }
    finally { await host.StopAsync(); }
}
finally
{
    temporary.Delete(recursive: true);
}
