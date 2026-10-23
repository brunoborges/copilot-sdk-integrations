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
/// <see cref="CopilotOptions.InProcess"/> hosts the runtime in this process. Runtime telemetry is enabled only when the
/// SDK starts the runtime as a child process (no <see cref="CopilotOptions.Endpoint"/>, not in-process) and
/// <c>OTEL_EXPORTER_OTLP_ENDPOINT</c> is configured. The runtime exports OTLP over HTTP only, so a
/// <c>grpc</c> <c>OTEL_EXPORTER_OTLP_PROTOCOL</c> is skipped. Message content is never captured.
/// </remarks>
internal sealed class CopilotClientOptionsConfigurator(IServiceProvider services) : ICopilotClientConfigurator
{
    internal const string OtlpEndpointKey = "OTEL_EXPORTER_OTLP_ENDPOINT";
    internal const string OtlpProtocolKey = "OTEL_EXPORTER_OTLP_PROTOCOL";
    internal const string LoggerCategory = "GitHub.Copilot.CopilotClient";

    /// <summary>The SDK's environment variable that selects the default connection when none is set.</summary>
    internal const string DefaultConnectionKey = "COPILOT_SDK_DEFAULT_CONNECTION";

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

        var token = copilotOptions.GitHubToken ?? ReadEnvironmentToken(services.GetService<IConfiguration>());
        if (token is not null)
        {
            options.GitHubToken = token;
        }

        if (copilotOptions.InProcess)
        {
            options.Connection ??= RuntimeConnection.ForInProcess();
        }

        if (IsInProcess(options))
        {
            // The SDK rejects TelemetryConfig for an in-process runtime.
            if (!string.IsNullOrWhiteSpace(services.GetService<IConfiguration>()?[OtlpEndpointKey]))
            {
                logger?.LogDebug("Copilot runtime telemetry is not enabled: the runtime runs in-process.");
            }

            return;
        }

        options.Telemetry ??= CreateTelemetry(services.GetService<IConfiguration>(), logger);
    }

    private static bool IsInProcess(CopilotClientOptions options) =>
        options.Connection is InProcessRuntimeConnection
        || (options.Connection is null
            && string.Equals(Environment.GetEnvironmentVariable(DefaultConnectionKey), "inprocess", StringComparison.OrdinalIgnoreCase));

    /// <summary>The environment variables checked, in order, when <see cref="CopilotOptions.GitHubToken"/> is not set.</summary>
    internal static readonly string[] TokenEnvironmentKeys = ["COPILOT_GITHUB_TOKEN", "GH_TOKEN", "GITHUB_TOKEN"];

    internal static string? ReadEnvironmentToken(IConfiguration? configuration)
    {
        foreach (var key in TokenEnvironmentKeys)
        {
            var value = configuration?[key];
            if (!string.IsNullOrWhiteSpace(value))
            {
                return value;
            }
        }

        return null;
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
