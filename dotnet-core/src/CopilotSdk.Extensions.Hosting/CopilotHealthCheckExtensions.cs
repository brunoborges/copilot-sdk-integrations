using CopilotSdk.Extensions.Hosting;
using Microsoft.Extensions.Diagnostics.HealthChecks;

namespace Microsoft.Extensions.DependencyInjection;

/// <summary>
/// Health check registration for the Copilot runtime.
/// </summary>
public static class CopilotHealthCheckExtensions
{
    /// <summary>
    /// Adds a health check that pings the shared Copilot runtime and checks its auth status.
    /// It never starts a client that hasn't been used yet.
    /// </summary>
    /// <remarks>
    /// Reports Healthy before the client starts; <paramref name="failureStatus"/> when the runtime does not respond;
    /// Degraded when the runtime is not signed in and no BYOK provider or per-user token provider is configured;
    /// otherwise Healthy, with <c>login</c>, <c>authType</c>, and <c>host</c> in the result data.
    /// </remarks>
    /// <param name="builder">The health checks builder.</param>
    /// <param name="name">The health check name. Defaults to <c>copilot</c>.</param>
    /// <param name="failureStatus">The status to report on failure. Defaults to <see cref="HealthStatus.Unhealthy"/>.</param>
    /// <param name="tags">Optional tags for filtering.</param>
    /// <param name="timeout">Optional timeout for the check.</param>
    /// <returns>The health checks builder.</returns>
    public static IHealthChecksBuilder AddCopilot(
        this IHealthChecksBuilder builder,
        string name = "copilot",
        HealthStatus? failureStatus = null,
        IEnumerable<string>? tags = null,
        TimeSpan? timeout = null)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentException.ThrowIfNullOrEmpty(name);

        return builder.AddCheck<CopilotHealthCheck>(name, failureStatus, tags ?? [], timeout);
    }
}
