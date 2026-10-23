using System.Text.Json;
using GitHub.Copilot;
using GitHub.Copilot.Rpc;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;
using CopilotSdk.Extensions.Hosting;
using Workshop;

namespace WorkshopTests;

public sealed class ContractsTests
{
    public static Exhibit Exhibit(int words = 110) => new(
        "Apollo 11", string.Join(" ", Enumerable.Repeat("Exploration", words)),
        ["What would you explore?", "How would you prepare?", "Who would you bring?"]);

    [Theory]
    [InlineData(100)]
    [InlineData(140)]
    public void NarrativeAcceptsExactBoundaries(int words) => Exhibit(words).Validate();

    [Theory]
    [InlineData(0)]
    [InlineData(99)]
    [InlineData(141)]
    public void NarrativeRejectsInvalidLengths(int words) =>
        Assert.Throws<InvalidDataException>(() => Exhibit(words).Validate());

    [Fact]
    public void InvalidTitleAndQuestionsAreRejected()
    {
        Assert.Throws<InvalidDataException>(() => (Exhibit() with { Title = "Two\nTitles" }).Validate());
        Assert.Throws<InvalidDataException>(() => (Exhibit() with { Questions = ["What?", "WHAT?", "Where?"] }).Validate());
        Assert.Throws<InvalidDataException>(() => (Exhibit() with { Questions = ["What?", "Where?", "No question"] }).Validate());
        Assert.Throws<InvalidDataException>(() => (Exhibit() with { Questions = ["What?"] }).Validate());
    }

    [Fact]
    public void FactsAreBoundedAndBundledSetsAreValid()
    {
        foreach (var facts in Contracts.FactSets.Values) Contracts.ValidateFacts(facts);
        Assert.Throws<InvalidDataException>(() => Contracts.ValidateFacts([]));
        Assert.Throws<InvalidDataException>(() => Contracts.ValidateFacts([" "]));
        Assert.Throws<InvalidDataException>(() => Contracts.ValidateFacts([new string('x', 501)]));
        Assert.Throws<InvalidDataException>(() => Contracts.ValidateFacts(Enumerable.Repeat("Fact", 21).ToArray()));
    }

    [Fact]
    public void ResearchRequiresWikipediaArticleCitations()
    {
        new Research("Context", [new Source("Apollo 11", "https://en.wikipedia.org/wiki/Apollo_11")]).Validate();
        Assert.Throws<InvalidDataException>(() =>
            new Research("Context", [new Source("Fake", "https://wikipedia.org.evil.test/wiki/Fake")]).Validate());
    }

    [Fact]
    public void ReportShapeAndCriteriaAreValidated()
    {
        Report().Validate();
        Assert.Throws<InvalidDataException>(() => new AccessibilityReport([]).Validate());
        Assert.Throws<InvalidDataException>(() => new AccessibilityReport([
            new("Issue", "Evidence", "9.9.9", "Fix"), .. Report().Findings[1..]
        ]).Validate());
    }

    public static AccessibilityReport Report() => new([
        new("Image", "Observed <img>", "1.1.1", "Add an alternative"),
        new("Headings", "Observed skipped heading structure", "1.3.1", "Use semantic structure"),
        new("Button", "Observed unnamed button", "4.1.2", "Add accessible name")
    ]);

    [Fact]
    public void HtmlEscapesDataAndHasAccessibleFilters()
    {
        var html = Contracts.ExhibitHtml(Exhibit() with { Title = "<script>alert(1)</script>" });
        Assert.DoesNotContain("<h1><script>", html, StringComparison.Ordinal);
        Assert.Contains("&lt;script&gt;", html, StringComparison.Ordinal);
        Assert.Contains("aria-live=\"polite\"", html, StringComparison.Ordinal);
        var report = Contracts.ReportHtml(Report(), Contracts.Target("https://example.com/?a=<b>"));
        Assert.Contains("Observed &lt;img&gt;", report, StringComparison.Ordinal);
        Assert.Contains("not a full WCAG conformance audit", report, StringComparison.Ordinal);
    }
}

public sealed class ToolAndPermissionTests : IDisposable
{
    private readonly DirectoryInfo _temporary = Directory.CreateTempSubdirectory("workshop-test-");
    private RunState State => new(Contracts.FactSets["apollo11"], _temporary.FullName, Contracts.Target("https://example.com/exhibit?a=1#title"));

    public void Dispose() => _temporary.Delete(recursive: true);

    private static PermissionRequestMcp Navigation(string url, string tool = "browser_navigate", string server = "playwright") =>
        new()
        {
            ServerName = server, ToolName = tool, ToolTitle = tool, ReadOnly = true,
            Args = JsonSerializer.SerializeToElement(new { url })
        };

    [Fact]
    public void OnlyExactTargetAndListedToolsAreAllowed()
    {
        var run = State;
        Assert.True(Agents.Allowed(Navigation(run.Target!.AbsoluteUri), run, "accessibility"));
        foreach (var target in new[] {
            "https://other.example/exhibit?a=1#title", "http://example.com/exhibit?a=1#title",
            "https://example.com/exhibit?a=2#title", "https://example.com/exhibit?a=1",
            "https://user@example.com/exhibit?a=1#title"
        })
            Assert.False(Agents.Allowed(Navigation(target), run, "accessibility"));
        Assert.False(Agents.Allowed(Navigation(run.Target.AbsoluteUri, "browser_evaluate"), run, "accessibility"));
        Assert.False(Agents.Allowed(Navigation(run.Target.AbsoluteUri, server: "other"), run, "accessibility"));
        Assert.False(Agents.Allowed(new PermissionRequestCustomTool { ToolName = "unknown", ToolDescription = "Unknown" }, run, "museum"));
        Assert.True(Agents.ResearchAllowed(Navigation("https://example.com", "search", "wikipedia")));
        Assert.False(Agents.ResearchAllowed(Navigation("https://example.com", "write", "wikipedia")));
    }

    [Fact]
    public async Task SessionConfigurationRejectsUnknownPermissions()
    {
        var run = State;
        var config = new SessionConfig();
        Agents.ConfigureSession(config, run, "accessibility");
        Assert.Equal(["playwright-browser_navigate", "read_latest_accessibility_snapshot", "accessibility_rule_lookup"], config.AvailableTools);
        Assert.Equal(SystemMessageMode.Replace, config.SystemMessage!.Mode);
        Assert.IsType<PermissionDecisionReject>(await config.OnPermissionRequest!(
            Navigation(run.Target!.AbsoluteUri, "browser_evaluate"), new PermissionInvocation { SessionId = "test" }));
    }

    [Fact]
    public void SnapshotReaderRejectsMissingSymlinkAndOversizedFiles()
    {
        var run = State;
        Assert.Throws<InvalidOperationException>(() => run.ReadLatestAccessibilitySnapshot());
        File.WriteAllBytes(Path.Combine(_temporary.FullName, "page-large.yml"), new byte[1_000_001]);
        File.WriteAllText(Path.Combine(_temporary.FullName, "outside.yml"), "Outside");
        File.CreateSymbolicLink(Path.Combine(_temporary.FullName, "page-link.yml"), Path.Combine(_temporary.FullName, "outside.yml"));
        Assert.Throws<InvalidOperationException>(() => run.ReadLatestAccessibilitySnapshot());
        File.WriteAllText(Path.Combine(_temporary.FullName, "page-current.yml"), "- heading \"Museum\" [level=1]");
        Assert.Contains("Museum", run.ReadLatestAccessibilitySnapshot(), StringComparison.Ordinal);
    }

    [Fact]
    public void PublicationRequiresLookupCalls()
    {
        var run = State;
        Assert.Throws<InvalidDataException>(run.VerifyMuseum);
        run.ApprovedFactLookup();
        run.VerifyMuseum();
        run.Research = new("Context", [new("Apollo 11", "https://en.wikipedia.org/wiki/Apollo_11")]);
        Assert.Throws<InvalidDataException>(run.VerifyMuseum);
        run.ApprovedWikipediaFactLookup();
        run.VerifyMuseum();
        Assert.Throws<InvalidDataException>(() => run.VerifyReport(ContractsTests.Report()));
    }

    [Fact]
    public async Task HostResolvesApplicationAgentAndExecutesMuseumFlowWithoutRuntime()
    {
        var run = State;
        var builder = Host.CreateApplicationBuilder();
        WorkshopConfiguration.AddDefaults(builder.Configuration);
        builder.Services.AddWorkshop(run, "museum", builder.Configuration);
        builder.Services.AddKeyedSingleton<IChatClient>("museum", new ScriptedChatClient(() =>
        {
            run.ApprovedFactLookup();
            return ContractsTests.Exhibit();
        }));
        using var host = builder.Build();
        await host.StartAsync(TestContext.Current.CancellationToken);
        try
        {
            var response = await host.Services.GetRequiredKeyedService<IChatClient>("museum").GetResponseAsync<Exhibit>(
                "Create exhibit.", cancellationToken: TestContext.Current.CancellationToken);
            response.Result.Validate();
            run.VerifyMuseum();
        }
        finally { await host.StopAsync(TestContext.Current.CancellationToken); }
    }

    [Fact]
    public async Task HostResolvesApplicationAgentAndExecutesAccessibilityFlowWithoutRuntime()
    {
        var run = State;
        File.WriteAllText(Path.Combine(_temporary.FullName, "page-current.yml"), "- heading \"Museum\" [level=1]");
        var builder = Host.CreateApplicationBuilder();
        WorkshopConfiguration.AddDefaults(builder.Configuration);
        builder.Services.AddWorkshop(run, "accessibility", builder.Configuration);
        builder.Services.AddKeyedSingleton<IChatClient>("accessibility", new ScriptedChatClient(() =>
        {
            run.ReadLatestAccessibilitySnapshot();
            foreach (var finding in ContractsTests.Report().Findings) run.AccessibilityRuleLookup(finding.Criterion);
            return ContractsTests.Report();
        }));
        using var host = builder.Build();
        await host.StartAsync(TestContext.Current.CancellationToken);
        try
        {
            var response = await host.Services.GetRequiredKeyedService<IChatClient>("accessibility").GetResponseAsync<AccessibilityReport>(
                "Review target.", cancellationToken: TestContext.Current.CancellationToken);
            response.Result.Validate();
            run.VerifyReport(response.Result);
        }
        finally { await host.StopAsync(TestContext.Current.CancellationToken); }
    }

    [Fact]
    public void PackagedPromptsAndNativeConfigurationAreAvailable()
    {
        Assert.Contains("museum curator", Prompts.Read("curator"), StringComparison.Ordinal);
        Assert.Contains("browser-observable", Prompts.Read("reviewer"), StringComparison.Ordinal);
        Assert.Contains("Wikipedia", Prompts.Read("researcher"), StringComparison.Ordinal);
        Assert.Throws<InvalidOperationException>(() => Prompts.Read("missing"));
        var builder = Host.CreateApplicationBuilder();
        WorkshopConfiguration.AddDefaults(builder.Configuration);
        builder.Configuration["Copilot:Agents:museum:Model"] = "configured-test-model";
        builder.Configuration["Copilot:Agents:museum:ResponseTimeout"] = "00:00:45";
        builder.Services.AddWorkshop(State, "museum", builder.Configuration);
        using var host = builder.Build();
        var options = host.Services.GetRequiredService<IOptionsMonitor<CopilotOptions>>().Get("museum");
        Assert.Equal("configured-test-model", options.Model);
        Assert.Equal(TimeSpan.FromSeconds(45), options.ResponseTimeout);
    }

    private sealed class ScriptedChatClient(Func<object> response) : IChatClient
    {
        public Task<ChatResponse> GetResponseAsync(IEnumerable<ChatMessage> messages, ChatOptions? options = null,
            CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult(new ChatResponse(new ChatMessage(ChatRole.Assistant,
                JsonSerializer.Serialize(response(), Contracts.Json))));
        }

        public IAsyncEnumerable<ChatResponseUpdate> GetStreamingResponseAsync(IEnumerable<ChatMessage> messages,
            ChatOptions? options = null, CancellationToken cancellationToken = default) =>
            throw new NotSupportedException("The sample requests validated, buffered structured results.");
        public object? GetService(Type serviceType, object? serviceKey = null) => null;
        public void Dispose() { }
    }
}
