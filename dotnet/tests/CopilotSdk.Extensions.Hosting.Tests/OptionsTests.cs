using GitHub.Copilot;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting.Tests;

internal static class TestHost
{
    public static ServiceProvider Build(Action<IServiceCollection> configure, Dictionary<string, string?>? settings = null)
    {
        var services = new ServiceCollection();
        services.AddSingleton<IConfiguration>(new ConfigurationBuilder().AddInMemoryCollection(settings ?? []).Build());
        configure(services);
        return services.BuildServiceProvider(new ServiceProviderOptions { ValidateScopes = true, ValidateOnBuild = true });
    }

    public static CopilotOptions GetOptions(this IServiceProvider services, string name = "") =>
        services.GetRequiredService<IOptionsMonitor<CopilotOptions>>().Get(name);
}

public sealed class OptionsTests
{
    [Fact]
    public void Configuration_OverridesCode()
    {
        using var sp = TestHost.Build(
            s => s.AddCopilot(o => { o.Model = "code-model"; o.Instructions = "code"; }),
            new() { ["Copilot:Model"] = "config-model", ["Copilot:ResponseTimeout"] = "00:00:30" });

        var o = sp.GetOptions();

        Assert.Equal("config-model", o.Model);
        Assert.Equal("code", o.Instructions);
        Assert.Equal(TimeSpan.FromSeconds(30), o.ResponseTimeout);
    }

    [Fact]
    public void NamedAgent_InheritsDefault_ThenOverlaysOwnCodeAndSection()
    {
        using var sp = TestHost.Build(
            s =>
            {
                s.AddCopilot(o => { o.Model = "default-code"; o.Instructions = "default"; o.ReasoningEffort = "low"; });
                s.AddCopilot("triage", o => { o.Instructions = "triage-code"; o.ReasoningEffort = "high"; });
            },
            new()
            {
                ["Copilot:Model"] = "default-config",
                ["Copilot:ReasoningEffort"] = "medium",
                ["Copilot:Agents:triage:Model"] = "triage-config",
            });

        var d = sp.GetOptions();
        var t = sp.GetOptions("triage");

        Assert.Equal(("default-config", "default", "medium"), (d.Model, d.Instructions, d.ReasoningEffort));
        // Named code beats the shared Copilot section; the named section beats everything.
        Assert.Equal(("triage-config", "triage-code", "high"), (t.Model, t.Instructions, t.ReasoningEffort));
    }

    [Fact]
    public void AgentsSection_IsNotBoundIntoDefaultAgent()
    {
        using var sp = TestHost.Build(
            s => s.AddCopilot(),
            new() { ["Copilot:Agents:triage:Model"] = "triage-config" });

        Assert.Null(sp.GetOptions().Model);
    }

    [Fact]
    public void Delegates_AndConfigureLists_ArePreservedAndInherited()
    {
        Func<IServiceProvider, CancellationToken, Task<string?>> tokens = (_, _) => Task.FromResult<string?>("t");
        using var sp = TestHost.Build(
            s =>
            {
                s.AddCopilot(o =>
                {
                    o.GitHubTokenProvider = tokens;
                    o.ConfigureSession(c => c.ExcludedTools = ["a"]);
                    o.ConfigureClient(c => c.BaseDirectory = "/x");
                });
                s.AddCopilot("triage", o => o.ConfigureSession(c => c.Streaming = true));
            },
            new() { ["Copilot:Model"] = "m" });

        var d = sp.GetOptions();
        var t = sp.GetOptions("triage");

        Assert.Same(tokens, d.GitHubTokenProvider);
        Assert.Same(tokens, t.GitHubTokenProvider);
        Assert.Single(d.SessionConfigurations);
        Assert.Equal(2, t.SessionConfigurations.Count);
        Assert.Single(d.ClientConfigurations);
    }

    [Fact]
    public void McpServers_BindAndMerge_ForNamedAgents()
    {
        using var sp = TestHost.Build(
            s => { s.AddCopilot(); s.AddCopilot("triage"); },
            new()
            {
                ["Copilot:McpServers:docs:Url"] = "https://docs/mcp",
                ["Copilot:McpServers:docs:Tools:0"] = "search",
                ["Copilot:Agents:triage:McpServers:fs:Command"] = "npx",
                ["Copilot:Agents:triage:McpServers:fs:Args:0"] = "server-fs",
            });

        Assert.Equal(["docs"], sp.GetOptions().McpServers.Keys);
        Assert.Equal(["docs", "fs"], sp.GetOptions("triage").McpServers.Keys.Order());
        Assert.True(sp.GetOptions().McpServers.ContainsKey("DOCS"));
    }

    [Fact]
    public void Provider_BindsFromConfiguration()
    {
        using var sp = TestHost.Build(
            s => s.AddCopilot(),
            new()
            {
                ["Copilot:Provider:Type"] = "openai",
                ["Copilot:Provider:BaseUrl"] = "https://models/v1",
                ["Copilot:Provider:ApiKey"] = "k",
            });

        var p = sp.GetOptions().Provider;

        Assert.NotNull(p);
        Assert.Equal(("openai", "https://models/v1", "k"), (p.Type, p.BaseUrl, p.ApiKey));
    }

    [Fact]
    public void SessionConfigurator_MapsOptions()
    {
        using var sp = TestHost.Build(
            s => s.AddCopilot(o =>
            {
                o.Model = "m";
                o.ReasoningEffort = "high";
                o.Instructions = "Be brief.";
                o.McpServers["docs"] = new()
                {
                    Url = "https://docs/mcp",
                    Tools = ["search", "search"],
                    Headers = new Dictionary<string, string> { ["X-Key"] = "v" },
                    Timeout = 5000,
                };
                o.McpServers["fs"] = new()
                {
                    Type = "stdio",
                    Command = "npx",
                    Args = ["-y", "server-fs"],
                    Env = new Dictionary<string, string> { ["A"] = "1" },
                    WorkingDirectory = "/work",
                    Tools = ["read_file"],
                };
            }));

        var config = new SessionConfig();
        new CopilotOptionsSessionConfigurator(sp.GetRequiredService<IOptionsMonitor<CopilotOptions>>())
            .Configure(Options.DefaultName, config, sp);

        Assert.Equal("m", config.Model);
        Assert.Equal("high", config.ReasoningEffort);
        Assert.Equal(SystemMessageMode.Append, config.SystemMessage!.Mode);
        Assert.Equal("Be brief.", config.SystemMessage.Content);

        var http = Assert.IsType<McpHttpServerConfig>(config.McpServers!["docs"]);
        Assert.Equal("https://docs/mcp", http.Url);
        Assert.Equal(["search"], http.Tools);
        Assert.Equal(5000, http.Timeout);
        Assert.Equal("v", http.Headers!["X-Key"]);

        var stdio = Assert.IsType<McpStdioServerConfig>(config.McpServers["fs"]);
        Assert.Equal("npx", stdio.Command);
        Assert.Equal(["-y", "server-fs"], stdio.Args);
        Assert.Equal("1", stdio.Env!["A"]);
        Assert.Equal("/work", stdio.WorkingDirectory);
        Assert.Equal(["read_file"], stdio.Tools);
    }

    [Theory]
    [InlineData("sse", null, null, "Http")]
    [InlineData("HTTP", null, null, "Http")]
    [InlineData("local", null, null, "Stdio")]
    [InlineData(null, "https://x", null, "Http")]
    [InlineData(null, null, "npx", "Stdio")]
    [InlineData(null, null, null, "Unknown")]
    [InlineData("websocket", "https://x", null, "Unknown")]
    public void McpTransport_Resolves(string? type, string? url, string? command, string expected)
    {
        Assert.Equal(expected, CopilotMcpServerMapper.ResolveTransport(new() { Type = type, Url = url, Command = command }).ToString());
    }

    [Fact]
    public void SessionConfigurator_LeavesConfigUntouched_WhenNothingSet()
    {
        using var sp = TestHost.Build(s => s.AddCopilot());

        var config = new SessionConfig();
        new CopilotOptionsSessionConfigurator(sp.GetRequiredService<IOptionsMonitor<CopilotOptions>>())
            .Configure(Options.DefaultName, config, sp);

        Assert.Null(config.Model);
        Assert.Null(config.SystemMessage);
        Assert.Null(config.McpServers);
        Assert.Null(config.Provider);
    }
}
