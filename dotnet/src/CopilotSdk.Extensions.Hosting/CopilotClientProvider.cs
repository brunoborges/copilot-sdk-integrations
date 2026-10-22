using GitHub.Copilot;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Default <see cref="ICopilotClientProvider"/>: builds <see cref="CopilotClientOptions"/> from every
/// <see cref="ICopilotClientConfigurator"/> and the default agent's <see cref="CopilotOptions.ConfigureClient"/>
/// actions, creates the client lazily, starts it once, and stops it with the host.
/// </summary>
internal sealed class CopilotClientProvider : ICopilotClientProvider, IAsyncDisposable, IDisposable
{
    private readonly Lazy<CopilotClient> _client;
    private readonly SemaphoreSlim _lifecycleLock = new(1, 1);
    private readonly ILogger _logger;
    private volatile bool _started;
    private int _disposed;

    public CopilotClientProvider(
        IEnumerable<ICopilotClientConfigurator> configurators,
        IOptionsMonitor<CopilotOptions> options,
        IServiceProvider services)
        : this(CreateFactory(configurators, options), services.GetService<ILoggerFactory>())
    {
    }

    internal CopilotClientProvider(Func<CopilotClient> clientFactory, ILoggerFactory? loggerFactory = null)
    {
        _client = new Lazy<CopilotClient>(clientFactory, LazyThreadSafetyMode.ExecutionAndPublication);
        _logger = loggerFactory?.CreateLogger<CopilotClientProvider>() ?? NullLogger<CopilotClientProvider>.Instance;
    }

    public CopilotClient Client
    {
        get
        {
            ObjectDisposedException.ThrowIf(_disposed != 0, this);
            return _client.Value;
        }
    }

    public bool IsStarted => _started;

    /// <summary>Starts the client. Overridable in tests through <see cref="StartClientAsync"/>.</summary>
    internal Func<CopilotClient, CancellationToken, Task> StartClientAsync { get; init; } = static (client, ct) => client.StartAsync(ct);

    internal Func<CopilotClient, Task> StopClientAsync { get; init; } = static client => client.StopAsync();

    internal Func<CopilotClient, Task> ForceStopClientAsync { get; init; } = static client => client.ForceStopAsync();

    public async ValueTask<CopilotClient> GetClientAsync(CancellationToken cancellationToken)
    {
        var client = Client;
        if (_started)
        {
            return client;
        }

        await _lifecycleLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (!_started)
            {
                Log.Starting(_logger);
                try
                {
                    await StartClientAsync(client, cancellationToken).ConfigureAwait(false);
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    Log.StartFailed(_logger, ex);
                    throw;
                }

                _started = true;
                Log.Started(_logger);
            }
        }
        finally
        {
            _lifecycleLock.Release();
        }

        return client;
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        if (!_started || _disposed != 0)
        {
            return;
        }

        // Not cancellable: shutdown must run to completion (force-stopping on timeout) once started.
        await _lifecycleLock.WaitAsync(CancellationToken.None).ConfigureAwait(false);
        try
        {
            if (!_started)
            {
                return;
            }

            var client = _client.Value;
            Log.Stopping(_logger);
            try
            {
                await StopClientAsync(client).WaitAsync(cancellationToken).ConfigureAwait(false);
                Log.Stopped(_logger);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                Log.ForceStopping(_logger, null);
                await ForceStopQuietlyAsync(client).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                Log.ForceStopping(_logger, ex);
                await ForceStopQuietlyAsync(client).ConfigureAwait(false);
            }
            finally
            {
                _started = false;
            }
        }
        finally
        {
            _lifecycleLock.Release();
        }
    }

    private async Task ForceStopQuietlyAsync(CopilotClient client)
    {
        try
        {
            await ForceStopClientAsync(client).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            Log.ForceStopFailed(_logger, ex);
        }
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

    private static Func<CopilotClient> CreateFactory(IEnumerable<ICopilotClientConfigurator> configurators, IOptionsMonitor<CopilotOptions> options)
    {
        var configuratorList = configurators.ToArray();
        return () => new CopilotClient(CreateClientOptions(configuratorList, options.Get(Options.DefaultName)));
    }

    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0)
        {
            return;
        }

        if (_client.IsValueCreated)
        {
            try
            {
                await _client.Value.DisposeAsync().ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                Log.DisposeFailed(_logger, ex);
            }
        }

        _started = false;
        _lifecycleLock.Dispose();
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0)
        {
            return;
        }

        if (_client.IsValueCreated)
        {
            try
            {
                _client.Value.Dispose();
            }
            catch (Exception ex)
            {
                Log.DisposeFailed(_logger, ex);
            }
        }

        _started = false;
        _lifecycleLock.Dispose();
    }

    private static class Log
    {
        private static readonly Action<ILogger, Exception?> s_starting = LoggerMessage.Define(
            LogLevel.Debug, new EventId(10, "CopilotClientStarting"), "Starting the Copilot client.");

        private static readonly Action<ILogger, Exception?> s_started = LoggerMessage.Define(
            LogLevel.Information, new EventId(11, "CopilotClientStarted"), "Copilot client started.");

        private static readonly Action<ILogger, Exception?> s_startFailed = LoggerMessage.Define(
            LogLevel.Error, new EventId(12, "CopilotClientStartFailed"), "Failed to start the Copilot client; the next call will retry.");

        private static readonly Action<ILogger, Exception?> s_stopping = LoggerMessage.Define(
            LogLevel.Debug, new EventId(13, "CopilotClientStopping"), "Stopping the Copilot client.");

        private static readonly Action<ILogger, Exception?> s_stopped = LoggerMessage.Define(
            LogLevel.Information, new EventId(14, "CopilotClientStopped"), "Copilot client stopped.");

        private static readonly Action<ILogger, Exception?> s_forceStopping = LoggerMessage.Define(
            LogLevel.Warning, new EventId(15, "CopilotClientForceStopping"), "The Copilot client did not stop gracefully; force-stopping it.");

        private static readonly Action<ILogger, Exception?> s_forceStopFailed = LoggerMessage.Define(
            LogLevel.Error, new EventId(16, "CopilotClientForceStopFailed"), "Failed to force-stop the Copilot client.");

        private static readonly Action<ILogger, Exception?> s_disposeFailed = LoggerMessage.Define(
            LogLevel.Warning, new EventId(17, "CopilotClientDisposeFailed"), "Failed to dispose the Copilot client.");

        public static void Starting(ILogger logger) => s_starting(logger, null);

        public static void Started(ILogger logger) => s_started(logger, null);

        public static void StartFailed(ILogger logger, Exception ex) => s_startFailed(logger, ex);

        public static void Stopping(ILogger logger) => s_stopping(logger, null);

        public static void Stopped(ILogger logger) => s_stopped(logger, null);

        public static void ForceStopping(ILogger logger, Exception? ex) => s_forceStopping(logger, ex);

        public static void ForceStopFailed(ILogger logger, Exception ex) => s_forceStopFailed(logger, ex);

        public static void DisposeFailed(ILogger logger, Exception ex) => s_disposeFailed(logger, ex);
    }
}
