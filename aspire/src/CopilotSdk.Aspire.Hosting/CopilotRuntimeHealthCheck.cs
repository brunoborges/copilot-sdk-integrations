using Aspire.Hosting.ApplicationModel;
using Microsoft.Copilot;
using Microsoft.Extensions.Diagnostics.HealthChecks;

namespace CopilotSdk.Aspire.Hosting;

/// <summary>
/// AppHost health check for a Copilot runtime: connects with the SDK, pings, and checks sign-in.
/// </summary>
/// <remarks>
/// Unhealthy until the runtime answers and is signed in, so <c>WaitFor(copilot)</c> means "ready to serve prompts".
/// When every model on the runtime uses a BYOK provider, sign-in is not required.
/// </remarks>
internal sealed class CopilotRuntimeHealthCheck(CopilotRuntimeResource runtime) : IHealthCheck, IAsyncDisposable
{
    private readonly SemaphoreSlim _lock = new(1, 1);
    private CopilotClient? _client;
    private string? _clientConnectionString;

    public async Task<HealthCheckResult> CheckHealthAsync(HealthCheckContext context, CancellationToken cancellationToken = default)
    {
        // Resolving an unallocated endpoint waits for allocation; report instead of blocking.
        if (!runtime.IsExisting && !runtime.PrimaryEndpoint.IsAllocated)
        {
            return HealthCheckResult.Unhealthy("The Copilot runtime endpoint is not allocated yet.");
        }

        string? connectionString;
        try
        {
            connectionString = await runtime.ConnectionStringExpression.GetValueAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            return HealthCheckResult.Unhealthy("The Copilot runtime endpoint is not allocated yet.", ex);
        }

        if (!CopilotRuntimeConnectionString.TryParse(connectionString, out var host, out var port, out var token))
        {
            return HealthCheckResult.Unhealthy($"The connection string of '{runtime.Name}' has no valid endpoint.");
        }

        await _lock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var client = await GetClientAsync(connectionString!, host, port, token).ConfigureAwait(false);
            try
            {
                await client.PingAsync(cancellationToken: cancellationToken).ConfigureAwait(false);
                var auth = await client.GetAuthStatusAsync(cancellationToken).ConfigureAwait(false);

                var data = new Dictionary<string, object>
                {
                    ["authenticated"] = auth.IsAuthenticated,
                };
                if (auth.Login is { } login)
                {
                    data["login"] = login;
                }

                if (auth.AuthType is { } authType)
                {
                    data["authType"] = authType;
                }

                if (auth.IsAuthenticated || UsesOnlyProviders())
                {
                    return HealthCheckResult.Healthy(auth.StatusMessage, data);
                }

                return HealthCheckResult.Unhealthy(
                    "The Copilot runtime is not signed in. Sign in with 'copilot login' or 'gh auth login', or set a token with WithGitHubToken(...).",
                    data: data);
            }
            catch (Exception ex) when (ex is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
            {
                await ResetClientAsync().ConfigureAwait(false);
                return HealthCheckResult.Unhealthy("The Copilot runtime did not respond.", ex);
            }
        }
        finally
        {
            _lock.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        await ResetClientAsync().ConfigureAwait(false);
        _lock.Dispose();
    }

    private bool UsesOnlyProviders() => runtime.Models.Count > 0 && runtime.Models.All(m => m.Provider is not null);

    private async ValueTask<CopilotClient> GetClientAsync(string connectionString, string host, int port, string? token)
    {
        if (_client is not null && _clientConnectionString == connectionString)
        {
            return _client;
        }

        await ResetClientAsync().ConfigureAwait(false);
        // The runtime owns its home and tools; the check only pings it, so no client mode features are needed.
        _client = new CopilotClient(new CopilotClientOptions
        {
            Mode = CopilotClientMode.Empty,
            Runtime = RuntimeConnection.ForTcp(host, port, string.IsNullOrEmpty(token) ? null : token),
        });
        _clientConnectionString = connectionString;
        return _client;
    }

    private async ValueTask ResetClientAsync()
    {
        var client = _client;
        _client = null;
        _clientConnectionString = null;
        if (client is not null)
        {
            try
            {
                await client.DisposeAsync().ConfigureAwait(false);
            }
            catch (Exception)
            {
                // Best effort: the runtime may already be gone.
            }
        }
    }
}
