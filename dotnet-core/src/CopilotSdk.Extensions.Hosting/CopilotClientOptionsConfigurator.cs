using GitHub.Copilot;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Maps <see cref="CopilotOptions"/> client-level settings onto <see cref="CopilotClientOptions"/>: the GitHub token,
/// the runtime endpoint, the host's logger, and runtime OpenTelemetry export.
/// </summary>
/// <remarks>
/// Runtime telemetry is enabled only when the SDK starts the runtime (no <see cref="CopilotOptions.Endpoint"/>) and
/// <c>OTEL_EXPORTER_OTLP_ENDPOINT</c> is configured. The runtime exports OTLP over HTTP only, so a
/// <c>grpc</c> <c>OTEL_EXPORTER_OTLP_PROTOCOL</c> is skipped. Message content is never captured.
/// </remarks>
internal sealed class CopilotClientOptionsConfigurator(IServiceProvider services) : ICopilotClientConfigurator
{
    internal const string OtlpEndpointKey = "OTEL_EXPORTER_OTLP_ENDPOINT";
    internal const string OtlpProtocolKey = "OTEL_EXPORTER_OTLP_PROTOCOL";
    internal const string LoggerCategory = "GitHub.Copilot.CopilotClient";

    public void Configure(CopilotClientOptions options, CopilotOptions copilotOptions)
    {
        var loggerFactory = services.GetService<ILoggerFactory>();
        var logger = loggerFactory?.CreateLogger(LoggerCategory);
        if (logger is not null)
        {
            options.Logger ??= logger;
        }

        if (copilotOptions.Endpoint is not null)
        {
            var url = CopilotEndpoint.TryParse(copilotOptions.Endpoint, out var endpoint) ? endpoint.RuntimeUrl : copilotOptions.Endpoint;
            options.Connection = RuntimeConnection.ForUri(url, copilotOptions.ConnectionToken);
            return;
        }

        if (copilotOptions.GitHubToken is not null)
        {
            options.GitHubToken = copilotOptions.GitHubToken;
        }

        options.Telemetry ??= CreateTelemetry(services.GetService<IConfiguration>(), logger);
    }

    internal static TelemetryConfig? CreateTelemetry(IConfiguration? configuration, ILogger? logger)
    {
        var otlpEndpoint = configuration?[OtlpEndpointKey];
        if (string.IsNullOrWhiteSpace(otlpEndpoint))
        {
            return null;
        }

        var protocol = configuration?[OtlpProtocolKey];
        if (!string.IsNullOrWhiteSpace(protocol) && !protocol.StartsWith("http/", StringComparison.OrdinalIgnoreCase))
        {
            logger?.LogDebug(
                "Copilot runtime telemetry is not enabled: {ProtocolKey} is '{Protocol}', but the runtime exports OTLP over HTTP only.",
                OtlpProtocolKey,
                protocol);
            return null;
        }

        return new TelemetryConfig
        {
            OtlpEndpoint = otlpEndpoint,
            OtlpProtocol = string.IsNullOrWhiteSpace(protocol) ? null : protocol,
            ExporterType = "otlp-http",
            CaptureContent = false,
        };
    }
}
