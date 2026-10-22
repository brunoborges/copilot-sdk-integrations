using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Starts the shared client with the host when <see cref="CopilotOptions.EagerStart"/> is set, and stops it
/// with the host, force-stopping if shutdown times out.
/// </summary>
internal sealed class CopilotClientLifetime(ICopilotClientProvider provider, IOptionsMonitor<CopilotOptions> options) : IHostedService
{
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        if (options.Get(Options.DefaultName).EagerStart)
        {
            await provider.GetClientAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        if (!provider.IsStarted)
        {
            return;
        }

        // TODO(chat-client agent): harden (log failures, handle exceptions from StopAsync).
        var client = provider.Client;
        try
        {
            await client.StopAsync().WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            await client.ForceStopAsync().ConfigureAwait(false);
        }
    }
}
