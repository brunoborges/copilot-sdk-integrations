using GitHub.Copilot;
using Microsoft.Extensions.AI;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Lists, reads, and deletes the Copilot sessions behind <see cref="ChatOptions.ConversationId"/>. Inject it to build
/// a conversation list, restore a transcript, or let users delete their history.
/// </summary>
/// <remarks>
/// Every method starts the shared client on first use. With the SDK-managed runtime, sessions live in the app's own
/// runtime home, so only this app's conversations are listed. With <see cref="CopilotOptions.Endpoint"/>, every session
/// on the shared runtime is visible.
/// </remarks>
public interface ICopilotSessionStore
{
    /// <summary>Lists local sessions, most recently modified first.</summary>
    /// <param name="cancellationToken">The token to monitor for cancellation requests.</param>
    /// <returns>The session metadata.</returns>
    Task<IReadOnlyList<SessionMetadata>> ListAsync(CancellationToken cancellationToken = default);

    /// <summary>Gets one session's metadata.</summary>
    /// <param name="conversationId">The conversation (session) id.</param>
    /// <param name="cancellationToken">The token to monitor for cancellation requests.</param>
    /// <returns>The metadata, or <see langword="null"/> when the session does not exist.</returns>
    Task<SessionMetadata?> GetAsync(string conversationId, CancellationToken cancellationToken = default);

    /// <summary>
    /// Reads a conversation's user and assistant messages, in order, without resuming the session. Sub-agent messages,
    /// tool calls, and empty assistant messages are omitted; each message's
    /// <see cref="ChatMessage.RawRepresentation"/> is the SDK event.
    /// </summary>
    /// <param name="conversationId">The conversation (session) id.</param>
    /// <param name="cancellationToken">The token to monitor for cancellation requests.</param>
    /// <returns>The messages, or <see langword="null"/> when the session does not exist.</returns>
    Task<IReadOnlyList<ChatMessage>?> GetMessagesAsync(string conversationId, CancellationToken cancellationToken = default);

    /// <summary>Deletes a session and its history. Waits for a turn in flight on the conversation to finish first.</summary>
    /// <param name="conversationId">The conversation (session) id.</param>
    /// <param name="cancellationToken">The token to monitor for cancellation requests.</param>
    /// <returns><see langword="true"/> if the session was deleted; <see langword="false"/> if it did not exist.</returns>
    Task<bool> DeleteAsync(string conversationId, CancellationToken cancellationToken = default);
}
