using System.Collections.Concurrent;
using CopilotSdk.Extensions.Hosting;
using GitHub.Copilot;

namespace CopilotSdk.Extensions.Hosting.Tests;

/// <summary>A scripted <see cref="ICopilotSessionGateway"/> for chat client tests.</summary>
internal sealed class FakeSessionGateway : ICopilotSessionGateway
{
    private int _created;

    public ConcurrentQueue<(string? ResumedId, SessionConfigBase Config)> Calls { get; } = new();

    public ConcurrentQueue<FakeSession> Sessions { get; } = new();

    /// <summary>Configures each new session (scripts its replies).</summary>
    public Action<FakeSession>? Setup { get; set; }

    public Task<ICopilotSessionHandle> CreateSessionAsync(SessionConfig config, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        Calls.Enqueue((null, config));
        return Task.FromResult<ICopilotSessionHandle>(NewSession($"session-{Interlocked.Increment(ref _created)}", config));
    }

    public Task<ICopilotSessionHandle> ResumeSessionAsync(string sessionId, ResumeSessionConfig config, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        Calls.Enqueue((sessionId, config));
        return Task.FromResult<ICopilotSessionHandle>(NewSession(sessionId, config));
    }

    private FakeSession NewSession(string id, SessionConfigBase config)
    {
        var session = new FakeSession(id, config);
        Setup?.Invoke(session);
        Sessions.Enqueue(session);
        return session;
    }
}

internal sealed class FakeSession(string sessionId, SessionConfigBase config) : ICopilotSessionHandle
{
    private readonly List<Action<SessionEvent>> _handlers = [];

    public string SessionId { get; } = sessionId;

    public SessionConfigBase Config { get; } = config;

    public CopilotSession? Session => null;

    public List<MessageOptions> Sent { get; } = [];

    public int AbortCount;

    public bool Disposed { get; private set; }

    /// <summary>Non-streaming reply. Default: an assistant message echoing the prompt.</summary>
    public Func<FakeSession, MessageOptions, CancellationToken, Task<AssistantMessageEvent?>> OnSendAndWait { get; set; } =
        static (_, message, _) => Task.FromResult<AssistantMessageEvent?>(FakeEvents.Assistant($"echo: {message.Prompt}"));

    /// <summary>Streaming send: returns the message id; emit events with <see cref="Emit"/>.</summary>
    public Func<FakeSession, MessageOptions, CancellationToken, Task<string>> OnSend { get; set; } =
        static (_, _, _) => Task.FromResult("user-1");

    public IDisposable On(Action<SessionEvent> handler)
    {
        lock (_handlers)
        {
            _handlers.Add(handler);
        }

        return new Unsubscriber(() =>
        {
            lock (_handlers)
            {
                _handlers.Remove(handler);
            }
        });
    }

    public void Emit(SessionEvent evt)
    {
        Action<SessionEvent>[] handlers;
        lock (_handlers)
        {
            handlers = [.. _handlers];
        }

        foreach (var handler in handlers)
        {
            handler(evt);
        }
    }

    public Task<string> SendAsync(MessageOptions message, CancellationToken cancellationToken)
    {
        Sent.Add(message);
        return OnSend(this, message, cancellationToken);
    }

    public Task<AssistantMessageEvent?> SendAndWaitAsync(MessageOptions message, CancellationToken cancellationToken)
    {
        Sent.Add(message);
        return OnSendAndWait(this, message, cancellationToken);
    }

    public Task AbortAsync(CancellationToken cancellationToken)
    {
        Interlocked.Increment(ref AbortCount);
        return Task.CompletedTask;
    }

    public ValueTask DisposeAsync()
    {
        Disposed = true;
        return ValueTask.CompletedTask;
    }

    private sealed class Unsubscriber(Action action) : IDisposable
    {
        public void Dispose() => action();
    }
}

internal static class FakeEvents
{
    public static AssistantMessageEvent Assistant(string content, string messageId = "assistant-1", string? originatingMessageId = null, string? agentId = null) =>
        new()
        {
            AgentId = agentId,
            Data = new AssistantMessageData { Content = content, MessageId = messageId, OriginatingMessageId = originatingMessageId, Model = "test-model" },
        };

    public static UserMessageEvent User(string messageId) =>
        new() { Data = new UserMessageData { Content = "prompt", MessageId = messageId } };

    public static AssistantMessageDeltaEvent Delta(string text, string messageId = "assistant-1", string? agentId = null) =>
        new() { AgentId = agentId, Data = new AssistantMessageDeltaData { DeltaContent = text, MessageId = messageId } };

    public static AssistantUsageEvent Usage(long input, long output, string? agentId = null) =>
        new() { AgentId = agentId, Data = new AssistantUsageData { Model = "test-model", InputTokens = input, OutputTokens = output } };

    public static SessionIdleEvent Idle(bool aborted = false) =>
        new() { Data = new SessionIdleData { Aborted = aborted ? true : null } };

    public static SessionErrorEvent Error(string message) =>
        new() { Data = new SessionErrorData { ErrorType = "test", Message = message } };
}
