using GitHub.Copilot;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Default <see cref="ICopilotClientProvider"/>: builds <see cref="CopilotClientOptions"/> from every
/// <see cref="ICopilotClientConfigurator"/> and the default agent's <see cref="CopilotOptions.ConfigureClient"/>
/// actions, creates the client lazily, and starts it once.
/// </summary>
internal sealed class CopilotClientProvider : ICopilotClientProvider, IAsyncDisposable, IDisposable
{
    private readonly Lazy<CopilotClient> _client;
    private readonly SemaphoreSlim _startLock = new(1, 1);
    private volatile bool _started;

    public CopilotClientProvider(IEnumerable<ICopilotClientConfigurator> configurators, IOptionsMonitor<CopilotOptions> options)
    {
        var configuratorList = configurators.ToArray();
        _client = new Lazy<CopilotClient>(() => new CopilotClient(CreateClientOptions(configuratorList, options.Get(Options.DefaultName))));
    }

    public CopilotClient Client => _client.Value;

    public bool IsStarted => _started;

    public async ValueTask<CopilotClient> GetClientAsync(CancellationToken cancellationToken)
    {
        var client = Client;
        if (_started)
        {
            return client;
        }

        // TODO(chat-client agent): review start semantics (failed start retry, concurrency with StopAsync).
        await _startLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (!_started)
            {
                await client.StartAsync(cancellationToken).ConfigureAwait(false);
                _started = true;
            }
        }
        finally
        {
            _startLock.Release();
        }

        return client;
    }

    internal static CopilotClientOptions CreateClientOptions(IEnumerable<ICopilotClientConfigurator> configurators, CopilotOptions copilotOptions)
    {
        var clientOptions = new CopilotClientOptions();

        foreach (var configurator in configurators)
        {
            configurator.Configure(clientOptions, copilotOptions);
        }

        foreach (var configure in copilotOptions.ClientConfigurations)
        {
            configure(clientOptions);
        }

        return clientOptions;
    }

    public async ValueTask DisposeAsync()
    {
        if (_client.IsValueCreated)
        {
            await _client.Value.DisposeAsync().ConfigureAwait(false);
        }

        _startLock.Dispose();
    }

    public void Dispose()
    {
        if (_client.IsValueCreated)
        {
            _client.Value.Dispose();
        }

        _startLock.Dispose();
    }
}
