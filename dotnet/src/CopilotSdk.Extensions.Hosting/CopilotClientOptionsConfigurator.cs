using Microsoft.Copilot;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Maps <see cref="CopilotOptions"/> client-level settings onto <see cref="CopilotClientOptions"/>: the GitHub token,
/// the runtime connection, the host's logger, and runtime OpenTelemetry export.
/// </summary>
/// <remarks>
/// With an <see cref="CopilotOptions.Endpoint"/>, the client attaches to that runtime over TCP. Otherwise the SDK owns
/// the runtime: it starts it as a child process over stdio, or hosts it in this process with
/// <see cref="CopilotOptions.InProcess"/> (or the SDK's <c>COPILOT_SDK_DEFAULT_CONNECTION</c> environment variable).
/// Runtime telemetry is enabled only for an SDK-owned runtime when <c>OTEL_EXPORTER_OTLP_ENDPOINT</c> is configured.
/// The runtime exports OTLP over HTTP only, so a <c>grpc</c> <c>OTEL_EXPORTER_OTLP_PROTOCOL</c> is skipped. Message
/// content is never captured.
/// </remarks>
internal sealed class CopilotClientOptionsConfigurator(IServiceProvider services) : ICopilotClientConfigurator
{
    internal const string OtlpEndpointKey = "OTEL_EXPORTER_OTLP_ENDPOINT";
    internal const string OtlpProtocolKey = "OTEL_EXPORTER_OTLP_PROTOCOL";
    internal const string LoggerCategory = "Microsoft.Copilot.CopilotClient";

    /// <summary>The SDK's environment variable that selects the default connection when none is set.</summary>
    internal const string DefaultConnectionKey = "COPILOT_SDK_DEFAULT_CONNECTION";

    public void Configure(CopilotClientOptions options, CopilotOptions copilotOptions)
    {
        var loggerFactory = services.GetService<ILoggerFactory>();
        var logger = loggerFactory?.CreateLogger(LoggerCategory);
        if (logger is not null)
        {
            options.Observability.Logger ??= logger;
        }

        if (copilotOptions.Endpoint is not null)
        {
            options.Runtime = CreateTcpConnection(copilotOptions.Endpoint, copilotOptions.ConnectionToken);
            return;
        }

        var token = copilotOptions.GitHubToken ?? ReadEnvironmentToken(services.GetService<IConfiguration>());
        if (token is not null)
        {
            options.GitHub.Token = token;
        }

        options.Runtime ??= CreateOwnedConnection(copilotOptions);
        options.Observability.Telemetry ??= CreateTelemetry(services.GetService<IConfiguration>(), logger);
    }

    /// <summary>
    /// Attaches to the runtime at <paramref name="endpoint"/>. The SDK takes a bare host and port, so URL forms are
    /// translated here.
    /// </summary>
    internal static TcpRuntimeConnection CreateTcpConnection(string endpoint, string? connectionToken)
    {
        if (!CopilotEndpoint.TryParse(endpoint, out var parsed))
        {
            throw new InvalidOperationException(
                $"Copilot endpoint '{endpoint}' is not valid. Expected tcp://host:port, http(s)://host[:port], host:port, or a port number.");
        }

        return RuntimeConnection.ForTcp(parsed.ConnectionHost, parsed.Port, string.IsNullOrEmpty(connectionToken) ? null : connectionToken);
    }

    /// <summary>
    /// The runtime the SDK starts when no endpoint is set: in-process when <see cref="CopilotOptions.InProcess"/> is set,
    /// the SDK's <c>COPILOT_SDK_DEFAULT_CONNECTION</c> choice when that variable is set, and a child process otherwise.
    /// </summary>
    internal static OwnedRuntimeConnection CreateOwnedConnection(CopilotOptions copilotOptions)
    {
        if (copilotOptions.InProcess)
        {
            return RuntimeConnection.ForInProcess();
        }

        // The SDK defaults to in-process hosting; keep a child process unless the deployment chose otherwise.
        return string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable(DefaultConnectionKey))
            ? RuntimeConnection.ForSpawnedStdio()
            : RuntimeConnection.ForDefault();
    }

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
