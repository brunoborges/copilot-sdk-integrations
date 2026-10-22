using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Caps concurrent turns across the app when <see cref="CopilotOptions.MaxConcurrentTurns"/> is set on the default
/// agent. Extra turns wait in a bounded queue of <see cref="QueueFactor"/> × <c>MaxConcurrentTurns</c> callers;
/// when the queue is full the turn fails with <see cref="InvalidOperationException"/>.
/// </summary>
internal sealed class CopilotTurnLimiter : IDisposable
{
    /// <summary>Queue length as a multiple of <see cref="CopilotOptions.MaxConcurrentTurns"/>.</summary>
    public const int QueueFactor = 8;

    private readonly SemaphoreSlim? _semaphore;
    private readonly int _maxConcurrentTurns;
    private readonly int _queueLimit;
    private int _queued;

    public CopilotTurnLimiter(IOptionsMonitor<CopilotOptions> options)
        : this(options.Get(Options.DefaultName).MaxConcurrentTurns)
    {
    }

    internal CopilotTurnLimiter(int? maxConcurrentTurns)
    {
        if (maxConcurrentTurns is > 0 and var max)
        {
            _maxConcurrentTurns = max;
            _queueLimit = max > int.MaxValue / QueueFactor ? int.MaxValue : max * QueueFactor;
            _semaphore = new SemaphoreSlim(max, max);
        }
    }

    /// <summary>Waits for a turn slot. Dispose the result to release it. Returns a no-op when there is no cap.</summary>
    /// <exception cref="InvalidOperationException">The queue of waiting turns is full.</exception>
    public async ValueTask<IDisposable> AcquireAsync(CancellationToken cancellationToken)
    {
        if (_semaphore is null)
        {
            return NoopReleaser.Instance;
        }

        if (!_semaphore.Wait(0, CancellationToken.None))
        {
            if (Interlocked.Increment(ref _queued) > _queueLimit)
            {
                Interlocked.Decrement(ref _queued);
                throw new InvalidOperationException(
                    $"Too many concurrent Copilot turns: {_maxConcurrentTurns} are running and {_queueLimit} are queued " +
                    $"(Copilot:MaxConcurrentTurns = {_maxConcurrentTurns}). Retry later or raise MaxConcurrentTurns.");
            }

            try
            {
                await _semaphore.WaitAsync(cancellationToken).ConfigureAwait(false);
            }
            finally
            {
                Interlocked.Decrement(ref _queued);
            }
        }

        return new Releaser(_semaphore);
    }

    public void Dispose() => _semaphore?.Dispose();

    private sealed class Releaser(SemaphoreSlim semaphore) : IDisposable
    {
        private int _disposed;

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) == 0)
            {
                semaphore.Release();
            }
        }
    }

    private sealed class NoopReleaser : IDisposable
    {
        public static readonly NoopReleaser Instance = new();

        public void Dispose()
        {
        }
    }
}
