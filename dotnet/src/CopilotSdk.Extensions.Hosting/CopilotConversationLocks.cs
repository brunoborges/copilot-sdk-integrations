namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Async locks keyed by conversation id, so concurrent turns for the same conversation are queued instead of
/// interleaved. Entries are removed when no caller holds or waits for them. Registered as a singleton shared by
/// every agent (conversation ids are Copilot session ids, unique across the runtime).
/// </summary>
internal sealed class CopilotConversationLocks
{
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.Ordinal);

    /// <summary>The number of conversations currently locked or awaited (for tests).</summary>
    internal int Count
    {
        get
        {
            lock (_entries)
            {
                return _entries.Count;
            }
        }
    }

    /// <summary>Waits for exclusive access to <paramref name="conversationId"/>. Dispose the result to release it.</summary>
    public async ValueTask<IDisposable> AcquireAsync(string conversationId, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(conversationId);

        Entry entry;
        lock (_entries)
        {
            if (!_entries.TryGetValue(conversationId, out entry!))
            {
                entry = new Entry();
                _entries.Add(conversationId, entry);
            }

            entry.References++;
        }

        try
        {
            await entry.Semaphore.WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch
        {
            Release(conversationId, entry, acquired: false);
            throw;
        }

        return new Releaser(this, conversationId, entry);
    }

    private void Release(string conversationId, Entry entry, bool acquired)
    {
        lock (_entries)
        {
            if (acquired)
            {
                entry.Semaphore.Release();
            }

            if (--entry.References == 0)
            {
                _entries.Remove(conversationId);
                entry.Semaphore.Dispose();
            }
        }
    }

    private sealed class Entry
    {
        public SemaphoreSlim Semaphore { get; } = new(1, 1);

        public int References { get; set; }
    }

    private sealed class Releaser(CopilotConversationLocks owner, string conversationId, Entry entry) : IDisposable
    {
        private int _disposed;

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) == 0)
            {
                owner.Release(conversationId, entry, acquired: true);
            }
        }
    }
}
