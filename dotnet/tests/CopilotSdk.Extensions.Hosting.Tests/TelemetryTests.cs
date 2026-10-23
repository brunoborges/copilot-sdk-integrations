using GitHub.Copilot;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace CopilotSdk.Extensions.Hosting.Tests;

public sealed class TelemetryTests
{
    private static CopilotClientOptions ConfigureClient(Dictionary<string, string?>? settings, Action<CopilotOptions>? configure = null)
    {
        using var sp = TestHost.Build(s => { s.AddLogging(); s.AddCopilot(configure); }, settings);
        var options = new CopilotClientOptions();
        new CopilotClientOptionsConfigurator(sp).Configure(options, sp.GetOptions());
        return options;
    }

    [Fact]
    public void OtlpEndpoint_EnablesRuntimeTelemetry_WithoutContent()
    {
        var options = ConfigureClient(new() { ["OTEL_EXPORTER_OTLP_ENDPOINT"] = "http://localhost:4318" });

        Assert.NotNull(options.Telemetry);
        Assert.Equal("http://localhost:4318", options.Telemetry.OtlpEndpoint);
        Assert.Equal("otlp-http", options.Telemetry.ExporterType);
        Assert.False(options.Telemetry.CaptureContent);
        Assert.Null(options.Telemetry.OtlpProtocol);
    }

    [Theory]
    [InlineData("http/protobuf")]
    [InlineData("http/json")]
    public void HttpProtocol_IsPassedThrough(string protocol)
    {
        var options = ConfigureClient(new()
        {
            ["OTEL_EXPORTER_OTLP_ENDPOINT"] = "http://localhost:4318",
            ["OTEL_EXPORTER_OTLP_PROTOCOL"] = protocol,
        });

        Assert.Equal(protocol, options.Telemetry!.OtlpProtocol);
    }

    [Fact]
    public void GrpcProtocol_IsSkipped()
    {
        var options = ConfigureClient(new()
        {
            ["OTEL_EXPORTER_OTLP_ENDPOINT"] = "http://localhost:4317",
            ["OTEL_EXPORTER_OTLP_PROTOCOL"] = "grpc",
        });

        Assert.Null(options.Telemetry);
    }

    [Fact]
    public void NoOtlpEndpoint_NoTelemetry() => Assert.Null(ConfigureClient(null).Telemetry);

    [Fact]
    public void ExternalRuntime_NoTelemetry_AndNormalizesTcpEndpoint()
    {
        var options = ConfigureClient(
            new() { ["OTEL_EXPORTER_OTLP_ENDPOINT"] = "http://localhost:4318" },
            o => { o.Endpoint = "tcp://runtime:5000"; o.ConnectionToken = "secret"; });

        Assert.Null(options.Telemetry);
        var connection = Assert.IsType<UriRuntimeConnection>(options.Connection);
        Assert.Equal("runtime:5000", connection.Url);
        Assert.Equal("secret", connection.ConnectionToken);
    }

    [Theory]
    [InlineData("5000", "5000")]
    [InlineData("localhost:5000", "localhost:5000")]
    [InlineData("http://localhost:5000", "http://localhost:5000")]
    [InlineData("tcp://[::1]:5000", "[::1]:5000")]
    public void Endpoint_IsPassedToForUri(string endpoint, string expected)
    {
        var options = ConfigureClient(null, o => o.Endpoint = endpoint);

        Assert.Equal(expected, Assert.IsType<UriRuntimeConnection>(options.Connection).Url);
    }

    [Fact]
    public void GitHubToken_And_Logger_AreApplied()
    {
        var options = ConfigureClient(null, o => o.GitHubToken = "ghp");

        Assert.Equal("ghp", options.GitHubToken);
        Assert.NotNull(options.Logger);
    }

    [Fact]
    public void ClientConfigurations_RunAfterConfigurators()
    {
        using var sp = TestHost.Build(
            s => { s.AddLogging(); s.AddCopilot(o => o.ConfigureClient(c => c.Telemetry = null)); },
            new() { ["OTEL_EXPORTER_OTLP_ENDPOINT"] = "http://localhost:4318" });

        var options = CopilotClientProvider.CreateClientOptions(sp.GetServices<ICopilotClientConfigurator>(), sp.GetOptions());

        Assert.Null(options.Telemetry);
    }

    [Fact]
    public void ChatClient_IsWrappedWithOpenTelemetry_AndForwardsGetService()
    {
        using var sp = TestHost.Build(s => { s.AddLogging(); s.AddCopilot(); s.AddCopilot("triage"); });

        var chat = sp.GetRequiredService<IChatClient>();
        var triage = sp.GetRequiredKeyedService<IChatClient>("triage");

        Assert.IsType<OpenTelemetryChatClient>(chat);
        Assert.NotNull(chat.GetService<CopilotChatClient>());
        Assert.Equal("triage", triage.GetService<CopilotChatClient>()!.AgentName);
        Assert.Same(sp.GetRequiredService<CopilotClient>(), chat.GetService<CopilotClient>());
    }

    [Fact]
    public void ChatClient_DoesNotRecordSensitiveData()
    {
        using var sp = TestHost.Build(s => { s.AddLogging(b => b.SetMinimumLevel(LogLevel.Trace)); s.AddCopilot(); });

        var otel = Assert.IsType<OpenTelemetryChatClient>(sp.GetRequiredService<IChatClient>());

        Assert.False(otel.EnableSensitiveData);
    }

    [Fact]
    public void InProcess_UsesInProcessConnection_AndSkipsTelemetry()
    {
        var options = ConfigureClient(
            new() { ["OTEL_EXPORTER_OTLP_ENDPOINT"] = "http://localhost:4318", ["GH_TOKEN"] = "env-token" },
            o => o.InProcess = true);

        Assert.IsType<InProcessRuntimeConnection>(options.Connection);
        Assert.Null(options.Telemetry);
        Assert.Equal("env-token", options.GitHubToken);
    }

    [Fact]
    public void InProcess_BindsFromConfiguration()
    {
        using var sp = TestHost.Build(s => s.AddCopilot(), new() { ["Copilot:InProcess"] = "true" });
        Assert.True(sp.GetOptions().InProcess);
    }
}
