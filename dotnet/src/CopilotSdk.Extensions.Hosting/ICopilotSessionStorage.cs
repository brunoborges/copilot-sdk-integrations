using GitHub.Copilot;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Session persistence operations on the shared client. A seam so the session store and cleanup service can be tested
/// without a runtime.
/// </summary>
internal interface ICopilotSessionStorage
{
    /// <summary>Whether the shared client has started.</summary>
    bool IsStarted { get; }

    Task<IList<SessionMetadata>> ListAsync(CancellationToken cancellationToken);

    Task<SessionMetadata?> GetAsync(string sessionId, CancellationToken cancellationToken);

    /// <summary>Reads every persisted event of an existing session, without resuming it.</summary>
    Task<IReadOnlyList<SessionEvent>> ReadEventsAsync(string sessionId, CancellationToken cancellationToken);

    Task DeleteAsync(string sessionId, CancellationToken cancellationToken);
}

/// <summary>Production <see cref="ICopilotSessionStorage"/> over the shared <see cref="CopilotClient"/>.</summary>
internal sealed class CopilotSessionStorage(ICopilotClientProvider clientProvider) : ICopilotSessionStorage
{
    private const int PageSize = 1000;

    public bool IsStarted => clientProvider.IsStarted;

    public async Task<IList<SessionMetadata>> ListAsync(CancellationToken cancellationToken)
    {
        var client = await clientProvider.GetClientAsync(cancellationToken).ConfigureAwait(false);
        return await client.ListSessionsAsync(cancellationToken: cancellationToken).ConfigureAwait(false);
    }

    public async Task<SessionMetadata?> GetAsync(string sessionId, CancellationToken cancellationToken)
    {
        var client = await clientProvider.GetClientAsync(cancellationToken).ConfigureAwait(false);
        return await client.GetSessionMetadataAsync(sessionId, cancellationToken).ConfigureAwait(false);
    }

    public async Task<IReadOnlyList<SessionEvent>> ReadEventsAsync(string sessionId, CancellationToken cancellationToken)
    {
        var client = await clientProvider.GetClientAsync(cancellationToken).ConfigureAwait(false);
        var events = new List<SessionEvent>();
        string? cursor = null;
        do
        {
            var page = await client.Rpc.Sessions.ReadPersistedEventsAsync(sessionId, cursor, PageSize, null, cancellationToken).ConfigureAwait(false);
            if (page.CursorStatus == GitHub.Copilot.Rpc.EventsCursorStatus.Expired)
            {
                throw new InvalidOperationException($"Reading the history of Copilot session '{sessionId}' expired before it completed.");
            }

            events.AddRange(page.Events);
            cursor = page.HasMore ? page.Cursor : null;
        }
        while (cursor is not null);

        return events;
    }

    public async Task DeleteAsync(string sessionId, CancellationToken cancellationToken)
    {
        var client = await clientProvider.GetClientAsync(cancellationToken).ConfigureAwait(false);
        await client.DeleteSessionAsync(sessionId, cancellationToken).ConfigureAwait(false);
    }
}
