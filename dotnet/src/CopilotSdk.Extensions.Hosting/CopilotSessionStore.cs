using GitHub.Copilot;
using Microsoft.Extensions.AI;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>Default <see cref="ICopilotSessionStore"/>.</summary>
internal sealed class CopilotSessionStore(ICopilotSessionStorage storage, CopilotConversationLocks locks) : ICopilotSessionStore
{
    public async Task<IReadOnlyList<SessionMetadata>> ListAsync(CancellationToken cancellationToken = default)
    {
        var sessions = await storage.ListAsync(cancellationToken).ConfigureAwait(false);
        return sessions.Where(static s => !s.IsRemote).OrderByDescending(static s => s.ModifiedTime).ToList();
    }

    public Task<SessionMetadata?> GetAsync(string conversationId, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(conversationId);
        return storage.GetAsync(conversationId, cancellationToken);
    }

    public async Task<IReadOnlyList<ChatMessage>?> GetMessagesAsync(string conversationId, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(conversationId);
        if (await storage.GetAsync(conversationId, cancellationToken).ConfigureAwait(false) is null)
        {
            return null;
        }

        var events = await storage.ReadEventsAsync(conversationId, cancellationToken).ConfigureAwait(false);
        var messages = new List<ChatMessage>();
        foreach (var evt in events)
        {
            if (!string.IsNullOrEmpty(evt.AgentId))
            {
                continue;
            }

            switch (evt)
            {
                case UserMessageEvent user:
                    messages.Add(new ChatMessage(ChatRole.User, user.Data.Content)
                    {
                        MessageId = user.Data.MessageId,
                        CreatedAt = NullIfDefault(user.Timestamp),
                        RawRepresentation = user,
                    });
                    break;

                case AssistantMessageEvent assistant when !string.IsNullOrEmpty(assistant.Data.Content):
                    messages.Add(new ChatMessage(ChatRole.Assistant, assistant.Data.Content)
                    {
                        MessageId = assistant.Data.MessageId,
                        CreatedAt = NullIfDefault(assistant.Timestamp),
                        RawRepresentation = assistant,
                    });
                    break;
            }
        }

        return messages;
    }

    public async Task<bool> DeleteAsync(string conversationId, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(conversationId);
        using var _ = await locks.AcquireAsync(conversationId, cancellationToken).ConfigureAwait(false);
        if (await storage.GetAsync(conversationId, cancellationToken).ConfigureAwait(false) is null)
        {
            return false;
        }

        await storage.DeleteAsync(conversationId, cancellationToken).ConfigureAwait(false);
        return true;
    }

    private static DateTimeOffset? NullIfDefault(DateTimeOffset value) => value == default ? null : value;
}
