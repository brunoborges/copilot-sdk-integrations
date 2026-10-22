using GitHub.Copilot;
using Microsoft.Extensions.Diagnostics.HealthChecks;

/// <summary>Stops the shared runtime with the host, force-stopping it if shutdown times out.</summary>
public sealed class CopilotLifetime(CopilotClient copilot) : IHostedService
{
    public Task StartAsync(CancellationToken cancellationToken) => Task.CompletedTask; // the SDK starts on first use

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        try
        {
            await copilot.StopAsync().WaitAsync(cancellationToken);
        }
        catch (OperationCanceledException)
        {
            await copilot.ForceStopAsync();
        }
    }
}

/// <summary>Pings the runtime and checks that it is signed in.</summary>
public sealed class CopilotHealthCheck(CopilotClient copilot) : IHealthCheck
{
    public async Task<HealthCheckResult> CheckHealthAsync(HealthCheckContext context, CancellationToken cancellationToken = default)
    {
        try
        {
            await copilot.PingAsync(cancellationToken: cancellationToken);
            var auth = await copilot.GetAuthStatusAsync(cancellationToken);
            return auth.IsAuthenticated
                ? HealthCheckResult.Healthy($"Signed in as {auth.Login}.")
                : HealthCheckResult.Degraded($"Copilot runtime is not signed in: {auth.StatusMessage}");
        }
        catch (Exception ex) when (!cancellationToken.IsCancellationRequested)
        {
            return new HealthCheckResult(context.Registration.FailureStatus, "Copilot runtime did not respond.", ex);
        }
    }
}
