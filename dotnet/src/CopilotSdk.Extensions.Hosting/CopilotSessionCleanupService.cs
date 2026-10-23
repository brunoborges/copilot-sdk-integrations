using GitHub.Copilot;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Deletes sessions not modified within <see cref="CopilotOptions.SessionRetention"/>. Does nothing when it is unset.
/// </summary>
/// <remarks>
/// Sweeps every half retention period, clamped to between one minute and one hour. A sweep is skipped while the
/// client has not started, so cleanup never starts the runtime on its own. Conversations with a turn in flight are
/// skipped until the next sweep.
/// </remarks>
internal sealed class CopilotSessionCleanupService(
    ICopilotSessionStorage storage,
    CopilotConversationLocks locks,
    IOptionsMonitor<CopilotOptions> options,
    IServiceProvider services) : BackgroundService
{
    private static readonly TimeSpan MinInterval = TimeSpan.FromMinutes(1);
    private static readonly TimeSpan MaxInterval = TimeSpan.FromHours(1);

    private readonly TimeProvider _time = services.GetService<TimeProvider>() ?? TimeProvider.System;
    private readonly ILogger _logger = services.GetService<ILoggerFactory>()?.CreateLogger<CopilotSessionCleanupService>()
        ?? NullLogger<CopilotSessionCleanupService>.Instance;

    internal static TimeSpan GetInterval(TimeSpan retention) =>
        TimeSpan.FromTicks(Math.Clamp(retention.Ticks / 2, MinInterval.Ticks, MaxInterval.Ticks));

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (options.Get(Options.DefaultName).SessionRetention is not { } retention)
        {
            return;
        }

        using var timer = new PeriodicTimer(GetInterval(retention), _time);
        try
        {
            while (await timer.WaitForNextTickAsync(stoppingToken).ConfigureAwait(false))
            {
                try
                {
                    await SweepAsync(retention, stoppingToken).ConfigureAwait(false);
                }
                catch (Exception ex) when (!stoppingToken.IsCancellationRequested)
                {
                    _logger.LogWarning(ex, "Copilot session cleanup failed; retrying at the next sweep.");
                }
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
        }
    }

    /// <summary>Deletes expired sessions once. Returns the number deleted.</summary>
    internal async Task<int> SweepAsync(TimeSpan retention, CancellationToken cancellationToken)
    {
        if (!storage.IsStarted)
        {
            return 0;
        }

        var cutoff = _time.GetUtcNow() - retention;
        var sessions = await storage.ListAsync(cancellationToken).ConfigureAwait(false);
        var deleted = 0;
        foreach (var session in sessions)
        {
            if (session.IsRemote || session.ModifiedTime >= cutoff)
            {
                continue;
            }

            using var held = locks.TryAcquire(session.SessionId);
            if (held is null)
            {
                continue;
            }

            if (await storage.GetAsync(session.SessionId, cancellationToken).ConfigureAwait(false) is not { } current
                || current.ModifiedTime >= cutoff)
            {
                continue;
            }

            try
            {
                await storage.DeleteAsync(session.SessionId, cancellationToken).ConfigureAwait(false);
                deleted++;
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                _logger.LogWarning(ex, "Could not delete expired Copilot session {SessionId}.", session.SessionId);
            }
        }

        if (deleted > 0)
        {
            _logger.LogInformation("Deleted {Count} Copilot sessions not modified since {Cutoff:O}.", deleted, cutoff);
        }

        return deleted;
    }
}
