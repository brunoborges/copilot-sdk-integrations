using GitHub.Copilot;
using Microsoft.Extensions.Diagnostics.HealthChecks;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Reports the health of the shared Copilot runtime connection. Never starts a lazy client.
/// </summary>
/// <remarks>
/// <list type="bullet">
/// <item>Client not started: <see cref="HealthStatus.Healthy"/>.</item>
/// <item>Ping or auth status call fails: the registration's failure status (Unhealthy by default).</item>
/// <item>Runtime not signed in, and neither a BYOK <see cref="CopilotOptions.Provider"/> nor a per-user
/// <see cref="CopilotOptions.GitHubTokenProvider"/> is configured on the default agent: <see cref="HealthStatus.Degraded"/>.</item>
/// <item>Otherwise: <see cref="HealthStatus.Healthy"/>, with <c>login</c>, <c>authType</c>, and <c>host</c> data.</item>
/// </list>
/// </remarks>
internal sealed class CopilotHealthCheck : IHealthCheck
{
    private readonly ICopilotClientProvider _provider;
    private readonly IOptionsMonitor<CopilotOptions> _options;
    private readonly Func<CopilotClient, CancellationToken, Task> _ping;
    private readonly Func<CopilotClient, CancellationToken, Task<GetAuthStatusResponse>> _getAuthStatus;

    public CopilotHealthCheck(ICopilotClientProvider provider, IOptionsMonitor<CopilotOptions> options)
        : this(
            provider,
            options,
            static (client, ct) => client.PingAsync(cancellationToken: ct),
            static (client, ct) => client.GetAuthStatusAsync(ct))
    {
    }

    internal CopilotHealthCheck(
        ICopilotClientProvider provider,
        IOptionsMonitor<CopilotOptions> options,
        Func<CopilotClient, CancellationToken, Task> ping,
        Func<CopilotClient, CancellationToken, Task<GetAuthStatusResponse>> getAuthStatus)
    {
        _provider = provider;
        _options = options;
        _ping = ping;
        _getAuthStatus = getAuthStatus;
    }

    public async Task<HealthCheckResult> CheckHealthAsync(HealthCheckContext context, CancellationToken cancellationToken = default)
    {
        if (!_provider.IsStarted)
        {
            return HealthCheckResult.Healthy("Copilot client not started.");
        }

        GetAuthStatusResponse auth;
        try
        {
            var client = _provider.Client;
            await _ping(client, cancellationToken).ConfigureAwait(false);
            auth = await _getAuthStatus(client, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
        {
            return new HealthCheckResult(context.Registration.FailureStatus, "Copilot runtime did not respond.", ex);
        }

        var data = new Dictionary<string, object>
        {
            ["authenticated"] = auth.IsAuthenticated,
        };
        AddIfSet(data, "login", auth.Login);
        AddIfSet(data, "authType", auth.AuthType);
        AddIfSet(data, "host", auth.Host);

        if (auth.IsAuthenticated)
        {
            return HealthCheckResult.Healthy("Copilot runtime is reachable and signed in.", data);
        }

        var options = _options.Get(Options.DefaultName);
        if (options.Provider is not null || options.GitHubTokenProvider is not null)
        {
            return HealthCheckResult.Healthy("Copilot runtime is reachable; per-user or BYOK credentials are used.", data);
        }

        var message = string.IsNullOrWhiteSpace(auth.StatusMessage)
            ? "Copilot runtime is reachable but not signed in."
            : $"Copilot runtime is reachable but not signed in: {auth.StatusMessage}";
        return HealthCheckResult.Degraded(message, data: data);
    }

    private static void AddIfSet(Dictionary<string, object> data, string key, string? value)
    {
        if (!string.IsNullOrEmpty(value))
        {
            data[key] = value;
        }
    }
}
