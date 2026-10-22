using CopilotSdk.Extensions.Hosting;
using GitHub.Copilot;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting.Tests;

public sealed class AddCopilotTests
{
    private static ServiceProvider Build(Action<IServiceCollection> configure, Dictionary<string, string?>? settings = null)
    {
        var services = new ServiceCollection();
        services.AddSingleton<IConfiguration>(new ConfigurationBuilder().AddInMemoryCollection(settings ?? []).Build());
        configure(services);
        return services.BuildServiceProvider(new ServiceProviderOptions { ValidateScopes = true, ValidateOnBuild = true });
    }

    [Fact]
    public void AddCopilot_RegistersChatClient()
    {
        using var sp = Build(s => s.AddCopilot());

        var chat = sp.GetRequiredService<IChatClient>();

        Assert.IsType<CopilotChatClient>(chat.GetService<CopilotChatClient>());
        Assert.Equal(Options.DefaultName, chat.GetService<CopilotChatClient>()!.AgentName);
        Assert.Single(sp.GetServices<IHostedService>().OfType<CopilotClientLifetime>());
    }

    [Fact]
    public void AddCopilot_Named_RegistersKeyedChatClient()
    {
        using var sp = Build(s =>
        {
            s.AddCopilot();
            s.AddCopilot("triage");
        });

        var triage = sp.GetRequiredKeyedService<IChatClient>("triage");

        Assert.Equal("triage", triage.GetService<CopilotChatClient>()!.AgentName);
        Assert.NotSame(sp.GetRequiredService<IChatClient>(), triage);
    }

    [Fact]
    public void AddCopilot_CalledTwice_SharesRegistrations()
    {
        var services = new ServiceCollection();
        services.AddCopilot();
        services.AddCopilot();
        services.AddCopilot("triage");

        Assert.Single(services, d => d.ServiceType == typeof(IChatClient) && !d.IsKeyedService);
        Assert.Single(services, d => d.ServiceType == typeof(CopilotClient));
        Assert.Single(services, d => d.ImplementationType == typeof(CopilotClientLifetime));
        Assert.Single(services, d => d.ImplementationType == typeof(CopilotPermissionsSessionConfigurator));
        Assert.Single(services, d => d.ServiceType == typeof(CopilotAgentRegistry));
    }

    [Fact]
    public void WithTools_RegistersToolTypeScopedAndRecordsRegistration()
    {
        var services = new ServiceCollection();
        services.AddCopilot().WithTools<SampleTools>();
        services.AddCopilot("triage").WithTools<SampleTools>().WithTool(() => "pong", "ping", "Replies pong.");

        var descriptor = Assert.Single(services, d => d.ServiceType == typeof(SampleTools));
        Assert.Equal(ServiceLifetime.Scoped, descriptor.Lifetime);

        var registry = CopilotToolRegistry.GetOrAdd(services);
        Assert.Single(registry.GetTools(Options.DefaultName));
        Assert.Equal(2, registry.GetTools("triage").Count());
    }

    [Fact]
    public void Options_HaveDefaults()
    {
        using var sp = Build(s => s.AddCopilot());

        var options = sp.GetRequiredService<IOptionsMonitor<CopilotOptions>>().Get(Options.DefaultName);

        Assert.Equal(TimeSpan.FromMinutes(2), options.ResponseTimeout);
        Assert.Equal("copilot", options.ConnectionName);
        Assert.False(options.EagerStart);
        Assert.Null(options.Model);
    }

    [Fact]
    public void Options_ConfigurationOverridesCode_AndNamedAgentsInherit()
    {
        using var sp = Build(
            s =>
            {
                s.AddCopilot(o => { o.Model = "code-model"; o.Instructions = "Default."; });
                s.AddCopilot("triage", o => o.Instructions = "Triage.");
            },
            new()
            {
                ["Copilot:Model"] = "config-model",
                ["Copilot:ResponseTimeout"] = "00:00:30",
                ["Copilot:McpServers:docs:Url"] = "https://docs.internal/mcp",
                ["Copilot:McpServers:docs:Tools:0"] = "search",
                ["Copilot:Provider:BaseUrl"] = "https://models.internal",
                ["Copilot:Agents:triage:Model"] = "triage-model",
            });

        var monitor = sp.GetRequiredService<IOptionsMonitor<CopilotOptions>>();
        var defaults = monitor.Get(Options.DefaultName);
        var triage = monitor.Get("triage");

        Assert.Equal("config-model", defaults.Model);
        Assert.Equal(TimeSpan.FromSeconds(30), defaults.ResponseTimeout);
        Assert.Equal(["search"], defaults.McpServers["docs"].Tools);
        Assert.Equal("https://models.internal", defaults.Provider?.BaseUrl);

        Assert.Equal("triage-model", triage.Model);
        Assert.Equal("Triage.", triage.Instructions);
        Assert.Equal(TimeSpan.FromSeconds(30), triage.ResponseTimeout);
    }

    [Fact]
    public void SessionConfigFactory_AppliesConfiguratorsThenConfigureSession()
    {
        using var sp = Build(s => s.AddCopilot(o =>
        {
            o.Model = "gpt-test";
            o.Instructions = "Be brief.";
            o.ConfigureSession(c => c.ExcludedTools = ["web_fetch"]);
        }));

        var config = sp.GetRequiredService<CopilotSessionConfigFactory>().CreateSessionConfig(Options.DefaultName, sp);

        Assert.Equal("gpt-test", config.Model);
        Assert.Equal("Be brief.", config.SystemMessage?.Content);
        Assert.Equal(["web_fetch"], config.ExcludedTools!);
        Assert.NotNull(config.OnPermissionRequest);
    }

    private sealed class SampleTools
    {
        [System.ComponentModel.Description("Says hello.")]
        public string Hello() => "hello";
    }
}
