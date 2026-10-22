using GitHub.Copilot;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Owns the single <see cref="CopilotClient"/> shared by every agent.
/// </summary>
internal interface ICopilotClientProvider
{
    /// <summary>The shared client, created on first access but not started. Injected as <see cref="CopilotClient"/>.</summary>
    CopilotClient Client { get; }

    /// <summary>Whether the client has been started (by first use, <see cref="CopilotOptions.EagerStart"/>, or the app).</summary>
    bool IsStarted { get; }

    /// <summary>Returns the shared client, starting it on first use.</summary>
    ValueTask<CopilotClient> GetClientAsync(CancellationToken cancellationToken);
}
