using GitHub.Copilot;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Creates and resumes Copilot sessions. A seam over <see cref="CopilotClient"/> so the chat client can be tested
/// without a runtime.
/// </summary>
internal interface ICopilotSessionGateway
{
    /// <summary>Creates a new session, starting the shared client on first use.</summary>
    Task<ICopilotSessionHandle> CreateSessionAsync(SessionConfig config, CancellationToken cancellationToken);

    /// <summary>Resumes an existing session, starting the shared client on first use.</summary>
    Task<ICopilotSessionHandle> ResumeSessionAsync(string sessionId, ResumeSessionConfig config, CancellationToken cancellationToken);
}

/// <summary>
/// The subset of <see cref="CopilotSession"/> the chat client uses. Disposing disconnects the session; its history
/// stays in the runtime and it can be resumed later.
/// </summary>
internal interface ICopilotSessionHandle : IAsyncDisposable
{
    /// <summary>The Copilot session id (the conversation id).</summary>
    string SessionId { get; }

    /// <summary>The underlying SDK session, or <see langword="null"/> for test fakes.</summary>
    CopilotSession? Session { get; }

    /// <summary>Subscribes to every session event. Dispose the result to unsubscribe.</summary>
    IDisposable On(Action<SessionEvent> handler);

    /// <summary>Sends a message without waiting; returns the message id.</summary>
    Task<string> SendAsync(MessageOptions message, CancellationToken cancellationToken);

    /// <summary>Sends a message and waits for the turn to finish; returns the final assistant message.</summary>
    Task<AssistantMessageEvent?> SendAndWaitAsync(MessageOptions message, CancellationToken cancellationToken);

    /// <summary>Aborts the turn in flight.</summary>
    Task AbortAsync(CancellationToken cancellationToken);
}

/// <summary>Production <see cref="ICopilotSessionGateway"/> over the shared <see cref="CopilotClient"/>.</summary>
internal sealed class CopilotSessionGateway(ICopilotClientProvider clientProvider) : ICopilotSessionGateway
{
    public async Task<ICopilotSessionHandle> CreateSessionAsync(SessionConfig config, CancellationToken cancellationToken)
    {
        var client = await clientProvider.GetClientAsync(cancellationToken).ConfigureAwait(false);
        var session = await client.CreateSessionAsync(config, cancellationToken).ConfigureAwait(false);
        return new Handle(session);
    }

    public async Task<ICopilotSessionHandle> ResumeSessionAsync(string sessionId, ResumeSessionConfig config, CancellationToken cancellationToken)
    {
        var client = await clientProvider.GetClientAsync(cancellationToken).ConfigureAwait(false);
        var session = await client.ResumeSessionAsync(sessionId, config, cancellationToken).ConfigureAwait(false);
        return new Handle(session);
    }

    private sealed class Handle(CopilotSession session) : ICopilotSessionHandle
    {
        public string SessionId => session.SessionId;

        public CopilotSession? Session => session;

        public IDisposable On(Action<SessionEvent> handler) => session.On(handler);

        public Task<string> SendAsync(MessageOptions message, CancellationToken cancellationToken) =>
            session.SendAsync(message, cancellationToken);

        // The chat client enforces ResponseTimeout itself, so the SDK's own wait timeout is disabled.
        public Task<AssistantMessageEvent?> SendAndWaitAsync(MessageOptions message, CancellationToken cancellationToken) =>
            session.SendAndWaitAsync(message, Timeout.InfiniteTimeSpan, cancellationToken);

        public Task AbortAsync(CancellationToken cancellationToken) => session.AbortAsync(cancellationToken);

        public ValueTask DisposeAsync() => session.DisposeAsync();
    }
}
