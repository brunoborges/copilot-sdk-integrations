using GitHub.Copilot;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Owns the single <see cref="CopilotClient"/> shared by every agent.
/// </summary>
internal interface ICopilotClientProvider
{
    /// <summary>The shared client, created on first access but not started. Injected as <see cref="CopilotClient"/>.</summary>
    CopilotClient Client { get; }

    /// <summary>Whether the client has been started (by first use or <see cref="CopilotOptions.EagerStart"/>) and not stopped.</summary>
    bool IsStarted { get; }

    /// <summary>Returns the shared client, starting it on first use. Concurrent callers share one start; a failed start is retried by the next caller.</summary>
    ValueTask<CopilotClient> GetClientAsync(CancellationToken cancellationToken);

    /// <summary>
    /// Stops the client if it was started: a graceful <see cref="CopilotClient.StopAsync"/>, then
    /// <see cref="CopilotClient.ForceStopAsync"/> if <paramref name="cancellationToken"/> fires or the graceful stop fails.
    /// </summary>
    async Task StopAsync(CancellationToken cancellationToken)
    {
        if (!IsStarted)
        {
            return;
        }

        try
        {
            await Client.StopAsync().WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception)
        {
            await Client.ForceStopAsync().ConfigureAwait(false);
        }
    }
}
