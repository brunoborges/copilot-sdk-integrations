using Microsoft.Extensions.Diagnostics.HealthChecks;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Reports the health of the shared Copilot runtime connection. Never starts a lazy client.
/// </summary>
internal sealed class CopilotHealthCheck(ICopilotClientProvider provider) : IHealthCheck
{
    public async Task<HealthCheckResult> CheckHealthAsync(HealthCheckContext context, CancellationToken cancellationToken = default)
    {
        if (!provider.IsStarted)
        {
            return HealthCheckResult.Healthy("Copilot client not started yet.");
        }

        // TODO(health agent): include GetAuthStatusAsync (unauthenticated -> context.Registration.FailureStatus).
        try
        {
            await provider.Client.PingAsync(cancellationToken: cancellationToken).ConfigureAwait(false);
            return HealthCheckResult.Healthy();
        }
        catch (Exception ex) when (ex is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
        {
            return new HealthCheckResult(context.Registration.FailureStatus, "Copilot runtime ping failed.", ex);
        }
    }
}
