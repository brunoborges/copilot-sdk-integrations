using System.Text.RegularExpressions;
using GitHub.Copilot;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Labels the provenance of the prompts Copilot agents send, using the SDK <see cref="MessageSource"/>
/// (<c>user</c>, <c>system</c>, or <c>agent-&lt;id&gt;</c>).
/// </summary>
/// <remarks>
/// <para>Each turn's source is chosen, in order of precedence, from:</para>
/// <list type="number">
/// <item><description>a <see cref="BeginScope(MessageSource)"/> scope active in the calling async flow (inside a
/// tool, only a scope opened by the tool itself counts; the outer caller's scope does not flow into tools);</description></item>
/// <item><description><c>agent-&lt;caller&gt;</c> when the call is made from a tool of another agent named
/// <c>&lt;caller&gt;</c> (agent-to-agent delegation within this library; the unnamed default agent is
/// <c>agent-default</c>);</description></item>
/// <item><description><see cref="CopilotOptions.MessageSource"/> (<c>Copilot:MessageSource</c> or
/// <c>Copilot:Agents:{name}:MessageSource</c>);</description></item>
/// <item><description>otherwise the field is omitted and the runtime treats the message as user input.</description></item>
/// </list>
/// <para>Inference never labels a message as <c>user</c>; only configuration or a scope can.</para>
/// <para>
/// <b>Security:</b> the source is caller-supplied provenance, not an authenticated identity. Never make
/// authorization decisions from it. The runtime may use it for auto-approval classification, queueing, steering, and
/// routing, and a non-user source is persisted in session history. <c>system</c> labels application-generated context;
/// it is not a system-role message and does not change the system prompt. Agent identifiers are validated against
/// <see cref="AgentIdPattern"/>; never put secrets or user-controlled text in them.
/// </para>
/// </remarks>
public static partial class CopilotMessageSources
{
    /// <summary>Accepted agent identifier syntax.</summary>
    public const string AgentIdPattern = @"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\z";

    private const string AgentPrefix = "agent-";

    private static readonly AsyncLocal<MessageSource?> s_scope = new();

    /// <summary>The source of the innermost <see cref="BeginScope(MessageSource)"/> scope in this async flow.</summary>
    public static MessageSource? Current => s_scope.Value;

    /// <summary>Returns the <c>agent-&lt;id&gt;</c> source after validating <paramref name="id"/>.</summary>
    /// <exception cref="ArgumentException"><paramref name="id"/> does not match <see cref="AgentIdPattern"/>.</exception>
    public static MessageSource Agent(string id)
    {
        if (!IsValidAgentId(id))
        {
            throw new ArgumentException($"Invalid Copilot message source agent id; expected {AgentIdPattern}.", nameof(id));
        }

        return MessageSource.Agent(id);
    }

    /// <summary>
    /// Parses <c>user</c>, <c>system</c>, or <c>agent-&lt;id&gt;</c>; a null or empty value yields
    /// <see langword="null"/> (runtime default).
    /// </summary>
    /// <exception cref="ArgumentException">For any other value.</exception>
    public static MessageSource? Parse(string? value)
    {
        if (string.IsNullOrEmpty(value))
        {
            return null;
        }

        if (value == MessageSource.User.Value)
        {
            return MessageSource.User;
        }

        if (value == MessageSource.System.Value)
        {
            return MessageSource.System;
        }

        if (value.StartsWith(AgentPrefix, StringComparison.Ordinal))
        {
            return Agent(value[AgentPrefix.Length..]);
        }

        throw new ArgumentException("Invalid Copilot message source; expected 'user', 'system', or 'agent-<id>'.", nameof(value));
    }

    /// <summary>
    /// Labels every agent call started in the current async flow with <paramref name="source"/> until the returned
    /// scope is disposed, for example <c>using var _ = CopilotMessageSources.BeginScope(MessageSource.System);</c>
    /// in a background job. Scopes nest; the innermost wins.
    /// </summary>
    /// <exception cref="ArgumentException"><paramref name="source"/> is not a supported, valid source.</exception>
    public static IDisposable BeginScope(MessageSource source)
    {
        ArgumentNullException.ThrowIfNull(source);
        var validated = Parse(source.Value) ?? throw new ArgumentException("A message source is required.", nameof(source));
        var previous = s_scope.Value;
        s_scope.Value = validated;
        return new Scope(previous);
    }

    /// <summary>Clears the scope for the rest of this async flow, so tools of a turn do not inherit the caller's scope.</summary>
    internal static void Suspend() => s_scope.Value = null;

    internal static bool IsValidAgentId(string? id) => id is not null && AgentId().IsMatch(id);

    [GeneratedRegex(AgentIdPattern)]
    private static partial Regex AgentId();

    private sealed class Scope(MessageSource? previous) : IDisposable
    {
        private bool _disposed;

        public void Dispose()
        {
            if (!_disposed)
            {
                _disposed = true;
                s_scope.Value = previous;
            }
        }
    }
}
