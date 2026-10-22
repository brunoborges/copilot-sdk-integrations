namespace CopilotSdk.Aspire;

/// <summary>
/// Aspire defaults for a Copilot client, bound from <c>Aspire:Copilot:Client</c> and
/// <c>Aspire:Copilot:Client:{connectionName}</c>.
/// </summary>
public sealed class CopilotClientSettings
{
    /// <summary>Whether to skip the <c>copilot_{connectionName}</c> health check. Defaults to <see langword="false"/>.</summary>
    public bool DisableHealthChecks { get; set; }

    /// <summary>Whether to skip registering the <c>Experimental.Microsoft.Extensions.AI</c> trace source. Defaults to <see langword="false"/>.</summary>
    public bool DisableTracing { get; set; }

    /// <summary>Whether to skip registering the <c>Experimental.Microsoft.Extensions.AI</c> meter. Defaults to <see langword="false"/>.</summary>
    public bool DisableMetrics { get; set; }

    /// <summary>The health check timeout; the health check service default when <see langword="null"/>.</summary>
    public TimeSpan? HealthCheckTimeout { get; set; }
}
