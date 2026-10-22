using GitHub.Copilot;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Maps <see cref="CopilotOptions"/> client-level settings onto <see cref="CopilotClientOptions"/>.
/// </summary>
internal sealed class CopilotClientOptionsConfigurator : ICopilotClientConfigurator
{
    public void Configure(CopilotClientOptions options, CopilotOptions copilotOptions)
    {
        if (copilotOptions.GitHubToken is not null)
        {
            options.GitHubToken = copilotOptions.GitHubToken;
        }

        if (copilotOptions.Endpoint is not null)
        {
            // TODO(config agent): normalize tcp://host:port for RuntimeConnection.ForUri.
            options.Connection = RuntimeConnection.ForUri(copilotOptions.Endpoint, copilotOptions.ConnectionToken);
        }

        // TODO(config agent): options.Logger from ILoggerFactory; options.Telemetry from OTEL_EXPORTER_OTLP_ENDPOINT.
    }
}
