using Microsoft.Copilot;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace CopilotSdk.Extensions.Hosting.Tests;

public sealed class TelemetryTests
{
    private static CopilotClientOptions ConfigureClient(Dictionary<string, string?>? settings, Action<CopilotOptions>? configure = null)
    {
        using var sp = TestHost.Build(s => { s.AddLogging(); s.AddCopilot(configure); }, settings);
        var options = new CopilotClientOptions { Mode = CopilotClientMode.Empty };
        new CopilotClientOptionsConfigurator(sp).Configure(options, sp.GetOptions());
        return options;
    }

    [Fact]
    public void OtlpEndpoint_EnablesRuntimeTelemetry_WithoutContent()
    {
        var options = ConfigureClient(new() { ["OTEL_EXPORTER_OTLP_ENDPOINT"] = "http://localhost:4318" });

        Assert.NotNull(options.Observability.Telemetry);
        Assert.Equal("http://localhost:4318", options.Observability.Telemetry.OtlpEndpoint);
        Assert.Equal("otlp-http", options.Observability.Telemetry.ExporterType);
        Assert.False(options.Observability.Telemetry.CaptureContent);
        Assert.Null(options.Observability.Telemetry.OtlpProtocol);
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

        Assert.Equal(protocol, options.Observability.Telemetry!.OtlpProtocol);
    }

    [Fact]
    public void GrpcProtocol_IsSkipped()
    {
        var options = ConfigureClient(new()
        {
            ["OTEL_EXPORTER_OTLP_ENDPOINT"] = "http://localhost:4317",
            ["OTEL_EXPORTER_OTLP_PROTOCOL"] = "grpc",
        });

        Assert.Null(options.Observability.Telemetry);
    }

    [Fact]
    public void NoOtlpEndpoint_NoTelemetry() => Assert.Null(ConfigureClient(null).Observability.Telemetry);

    [Fact]
    public void ExternalRuntime_NoTelemetry_AndNormalizesTcpEndpoint()
    {
        var options = ConfigureClient(
            new() { ["OTEL_EXPORTER_OTLP_ENDPOINT"] = "http://localhost:4318" },
            o => { o.Endpoint = "tcp://runtime:5000"; o.ConnectionToken = "secret"; });

        Assert.Null(options.Observability.Telemetry);
        var connection = Assert.IsType<TcpRuntimeConnection>(options.Runtime);
        Assert.Equal(("runtime", 5000), (connection.Host, connection.Port));
        Assert.Equal("secret", connection.ConnectionToken);
    }

    [Theory]
    [InlineData("5000", "localhost", 5000)]
    [InlineData("localhost:5000", "localhost", 5000)]
    [InlineData("http://localhost:5000", "localhost", 5000)]
    [InlineData("https://localhost/runtime", "localhost", 443)]
    [InlineData("tcp://[::1]:5000", "::1", 5000)]
    public void Endpoint_IsTranslatedToHostAndPort(string endpoint, string host, int port)
    {
        var options = ConfigureClient(null, o => o.Endpoint = endpoint);

        var connection = Assert.IsType<TcpRuntimeConnection>(options.Runtime);
        Assert.Equal((host, port), (connection.Host, connection.Port));
        Assert.Null(connection.ConnectionToken);
    }

    [Fact]
    public void NoEndpoint_StartsRuntimeAsChildProcess()
    {
        var options = ConfigureClient(null);

        Assert.IsType<SpawnedStdioRuntimeConnection>(options.Runtime);
    }

    [Fact]
    public void GitHubToken_And_Logger_AreApplied()
    {
        var options = ConfigureClient(null, o => o.GitHubToken = "ghp");

        Assert.Equal("ghp", options.GitHub.Token);
        Assert.NotNull(options.Observability.Logger);
    }

    [Fact]
    public void ClientConfigurations_RunAfterConfigurators()
    {
        using var sp = TestHost.Build(
            s => { s.AddLogging(); s.AddCopilot(o => o.ConfigureClient(c => c.Observability.Telemetry = null)); },
            new() { ["OTEL_EXPORTER_OTLP_ENDPOINT"] = "http://localhost:4318" });

        var options = CopilotClientProvider.CreateClientOptions(sp.GetServices<ICopilotClientConfigurator>(), sp.GetOptions());

        Assert.Null(options.Observability.Telemetry);
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
    public void InProcess_UsesInProcessConnection_AndExportsTelemetry()
    {
        var options = ConfigureClient(
            new() { ["OTEL_EXPORTER_OTLP_ENDPOINT"] = "http://localhost:4318", ["GH_TOKEN"] = "env-token" },
            o => o.InProcess = true);

        Assert.IsType<InProcessRuntimeConnection>(options.Runtime);
        Assert.Equal("http://localhost:4318", options.Observability.Telemetry?.OtlpEndpoint);
        Assert.Equal("env-token", options.GitHub.Token);
    }

    [Fact]
    public void InProcess_BindsFromConfiguration()
    {
        using var sp = TestHost.Build(s => s.AddCopilot(), new() { ["Copilot:InProcess"] = "true" });
        Assert.True(sp.GetOptions().InProcess);
    }
}
