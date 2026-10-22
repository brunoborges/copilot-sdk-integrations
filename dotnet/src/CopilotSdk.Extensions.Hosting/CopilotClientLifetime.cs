using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Starts the shared client with the host when <see cref="CopilotOptions.EagerStart"/> is set (otherwise it starts on
/// first use), and stops it with the host, force-stopping if the host's shutdown timeout fires.
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

    public Task StopAsync(CancellationToken cancellationToken) => provider.StopAsync(cancellationToken);
}
