using System.Collections.Concurrent;
using System.Diagnostics.CodeAnalysis;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// The caller's context for one turn, captured on the calling thread before the turn is sent. SDK callbacks
/// (tools, permissions, token refresh) run on the SDK's RPC thread and look it up by session id.
/// </summary>
/// <param name="ExecutionContext">The caller's execution context (<c>HttpContext</c>, <c>Activity</c>, logging scopes, culture), or <see langword="null"/> when flow was suppressed.</param>
/// <param name="Services">The service provider of the call (the <see cref="Microsoft.Extensions.AI.IChatClient"/>'s provider).</param>
/// <param name="AgentName">The agent name (<see cref="Microsoft.Extensions.Options.Options.DefaultName"/> for the default agent).</param>
/// <param name="CancellationToken">The turn's cancellation token (caller token linked with the response timeout).</param>
internal sealed record TurnContext(
    ExecutionContext? ExecutionContext,
    IServiceProvider Services,
    string AgentName,
    CancellationToken CancellationToken);

/// <summary>
/// Maps Copilot session ids to the <see cref="TurnContext"/> of the turn in flight. Registered as a singleton.
/// A session has at most one turn in flight because the chat client serializes calls per conversation.
/// </summary>
internal sealed class CopilotTurnContextRegistry
{
    private readonly ConcurrentDictionary<string, TurnContext> _contexts = new(StringComparer.Ordinal);

    /// <summary>Sets (or replaces) the context for <paramref name="sessionId"/>.</summary>
    public void Set(string sessionId, TurnContext context)
    {
        ArgumentNullException.ThrowIfNull(sessionId);
        ArgumentNullException.ThrowIfNull(context);
        _contexts[sessionId] = context;
    }

    /// <summary>Gets the context of the turn in flight for <paramref name="sessionId"/>.</summary>
    public bool TryGet(string sessionId, [NotNullWhen(true)] out TurnContext? context)
    {
        if (sessionId is null)
        {
            context = null;
            return false;
        }

        return _contexts.TryGetValue(sessionId, out context);
    }

    /// <summary>Removes the context for <paramref name="sessionId"/>.</summary>
    public void Remove(string sessionId)
    {
        ArgumentNullException.ThrowIfNull(sessionId);
        _contexts.TryRemove(sessionId, out _);
    }
}
