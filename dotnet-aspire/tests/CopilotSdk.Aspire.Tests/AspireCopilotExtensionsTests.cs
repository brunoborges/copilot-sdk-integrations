using CopilotSdk.Aspire;
using CopilotSdk.Extensions.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Diagnostics.HealthChecks;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Aspire.Tests;

public class AspireCopilotExtensionsTests
{
    private const string ModelConnectionString = "Endpoint=tcp://localhost:5000;ConnectionToken=s3cret;Model=gpt-5.4";

    private static HostApplicationBuilder CreateBuilder(params (string Key, string? Value)[] settings)
    {
        var builder = Host.CreateEmptyApplicationBuilder(new HostApplicationBuilderSettings());
        builder.Configuration.AddInMemoryCollection(settings.Select(s => new KeyValuePair<string, string?>(s.Key, s.Value)));
        return builder;
    }

    [Fact]
    public void AddCopilotClient_ReadsConnectionStringThroughCore()
    {
        var builder = CreateBuilder(("ConnectionStrings:chat", ModelConnectionString));

        builder.AddCopilotClient("chat");

        using var host = builder.Build();
        var options = host.Services.GetRequiredService<IOptionsMonitor<CopilotOptions>>().Get(Options.DefaultName);
        Assert.Equal("chat", options.ConnectionName);
        Assert.Equal("tcp://localhost:5000", options.Endpoint);
        Assert.Equal("s3cret", options.ConnectionToken);
        Assert.Equal("gpt-5.4", options.Model);
    }

    [Fact]
    public void AddCopilotClient_ConfigurationOverridesConnectionString()
    {
        var builder = CreateBuilder(("ConnectionStrings:chat", ModelConnectionString), ("Copilot:Model", "claude-sonnet-4.5"));

        builder.AddCopilotClient("chat", o => o.Instructions = "Be brief.");

        using var host = builder.Build();
        var options = host.Services.GetRequiredService<IOptionsMonitor<CopilotOptions>>().Get(Options.DefaultName);
        Assert.Equal("claude-sonnet-4.5", options.Model);
        Assert.Equal("Be brief.", options.Instructions);
    }

    [Fact]
    public void AddCopilotClient_MissingConnectionStringFailsWithHint()
    {
        var builder = CreateBuilder();

        builder.AddCopilotClient("chat");

        using var host = builder.Build();
        var ex = Assert.Throws<OptionsValidationException>(() => host.Services.GetRequiredService<IOptionsMonitor<CopilotOptions>>().Get(Options.DefaultName));
        Assert.Contains("ConnectionStrings:chat not found. Did you call WithReference", ex.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task AddCopilotClient_MissingConnectionStringFailsAtStartup()
    {
        var builder = CreateBuilder();
        builder.AddCopilotClient("chat");

        using var host = builder.Build();

        var ex = await Assert.ThrowsAsync<OptionsValidationException>(() => host.StartAsync(TestContext.Current.CancellationToken));
        Assert.Contains("ConnectionStrings:chat", ex.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void AddCopilotClient_ExplicitEndpointSkipsConnectionStringCheck()
    {
        var builder = CreateBuilder(("Copilot:Endpoint", "tcp://localhost:6000"));

        builder.AddCopilotClient("chat");

        using var host = builder.Build();
        var options = host.Services.GetRequiredService<IOptionsMonitor<CopilotOptions>>().Get(Options.DefaultName);
        Assert.Equal("tcp://localhost:6000", options.Endpoint);
    }

    [Fact]
    public void AddCopilotClient_RegistersReadyHealthCheck()
    {
        var builder = CreateBuilder(("ConnectionStrings:chat", ModelConnectionString));

        builder.AddCopilotClient("chat", configureSettings: s => s.HealthCheckTimeout = TimeSpan.FromSeconds(3));
        builder.AddCopilotClient("chat");

        using var host = builder.Build();
        var registrations = host.Services.GetRequiredService<IOptions<HealthCheckServiceOptions>>().Value.Registrations;
        var registration = Assert.Single(registrations);
        Assert.Equal("copilot_chat", registration.Name);
        Assert.Contains("ready", registration.Tags);
        Assert.Equal(TimeSpan.FromSeconds(3), registration.Timeout);
    }

    [Fact]
    public void Settings_BindFromGlobalAndNamedSections()
    {
        var builder = CreateBuilder(
            ("ConnectionStrings:chat", ModelConnectionString),
            ("Aspire:Copilot:Client:DisableTracing", "true"),
            ("Aspire:Copilot:Client:HealthCheckTimeout", "00:00:05"),
            ("Aspire:Copilot:Client:chat:DisableHealthChecks", "true"));

        CopilotClientSettings? captured = null;
        builder.AddCopilotClient("chat", configureSettings: s => captured = s);

        Assert.NotNull(captured);
        Assert.True(captured.DisableTracing);
        Assert.True(captured.DisableHealthChecks);
        Assert.False(captured.DisableMetrics);
        Assert.Equal(TimeSpan.FromSeconds(5), captured.HealthCheckTimeout);

        using var host = builder.Build();
        Assert.Empty(host.Services.GetRequiredService<IOptions<HealthCheckServiceOptions>>().Value.Registrations);
    }

    [Fact]
    public void AddCopilotClient_RegistersOpenTelemetryUnlessDisabled()
    {
        var enabled = CreateBuilder(("ConnectionStrings:chat", ModelConnectionString));
        enabled.AddCopilotClient("chat");
        Assert.Contains(enabled.Services, d => d.ServiceType.FullName == "OpenTelemetry.Trace.TracerProvider");
        Assert.Contains(enabled.Services, d => d.ServiceType.FullName == "OpenTelemetry.Metrics.MeterProvider");

        var disabled = CreateBuilder(("ConnectionStrings:chat", ModelConnectionString));
        disabled.AddCopilotClient("chat", configureSettings: s => (s.DisableTracing, s.DisableMetrics) = (true, true));
        Assert.DoesNotContain(disabled.Services, d => d.ServiceType.FullName == "OpenTelemetry.Trace.TracerProvider");
        Assert.DoesNotContain(disabled.Services, d => d.ServiceType.FullName == "OpenTelemetry.Metrics.MeterProvider");
    }

    [Fact]
    public void AddKeyedCopilotClient_ConfiguresNamedAgentAndSharedEndpoint()
    {
        var builder = CreateBuilder(("ConnectionStrings:reviewer", "Endpoint=tcp://localhost:5000;ConnectionToken=s3cret;Model=claude-sonnet-4.5"));

        builder.AddKeyedCopilotClient("reviewer");

        using var host = builder.Build();
        var monitor = host.Services.GetRequiredService<IOptionsMonitor<CopilotOptions>>();
        var named = monitor.Get("reviewer");
        Assert.Equal("reviewer", named.ConnectionName);
        Assert.Equal("claude-sonnet-4.5", named.Model);

        var shared = monitor.Get(Options.DefaultName);
        Assert.Equal("tcp://localhost:5000", shared.Endpoint);
        Assert.Equal("s3cret", shared.ConnectionToken);

        var registration = Assert.Single(host.Services.GetRequiredService<IOptions<HealthCheckServiceOptions>>().Value.Registrations);
        Assert.Equal("copilot_reviewer", registration.Name);
    }

    [Fact]
    public void AddKeyedCopilotClient_MissingConnectionStringFails()
    {
        var builder = CreateBuilder();

        builder.AddKeyedCopilotClient("reviewer");

        using var host = builder.Build();
        var ex = Assert.Throws<OptionsValidationException>(() => host.Services.GetRequiredService<IOptionsMonitor<CopilotOptions>>().Get("reviewer"));
        Assert.Contains("ConnectionStrings:reviewer not found", ex.Message, StringComparison.Ordinal);
    }
}
