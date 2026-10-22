using GitHub.Copilot;
using Microsoft.Extensions.AI;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// <see cref="IChatClient"/> over Copilot sessions for one agent. <see cref="ChatOptions.ConversationId"/> maps to
/// the Copilot session id.
/// </summary>
/// <remarks>
/// Created by <see cref="CopilotChatClientFactory"/> with <c>ActivatorUtilities</c>; the first constructor argument
/// is the agent name and any other parameters are resolved from DI.
/// </remarks>
internal sealed class CopilotChatClient(
    string agentName,
    ICopilotClientProvider clientProvider,
    CopilotSessionConfigFactory sessionConfigFactory,
    IServiceProvider services) : IChatClient
{
    public string AgentName { get; } = agentName;

    public Task<ChatResponse> GetResponseAsync(
        IEnumerable<ChatMessage> messages,
        ChatOptions? options = null,
        CancellationToken cancellationToken = default)
    {
        // TODO(chat-client agent): create/resume session via sessionConfigFactory + clientProvider, per-conversation
        // locking, MaxConcurrentTurns, GitHubTokenProvider, ResponseFormat -> structured output, ResponseTimeout and
        // cancellation -> session.AbortAsync().
        _ = (clientProvider, sessionConfigFactory, services);
        throw new NotImplementedException("CopilotChatClient.GetResponseAsync is not implemented yet.");
    }

    public IAsyncEnumerable<ChatResponseUpdate> GetStreamingResponseAsync(
        IEnumerable<ChatMessage> messages,
        ChatOptions? options = null,
        CancellationToken cancellationToken = default)
    {
        // TODO(chat-client agent): stream AssistantMessageDeltaEvent until SessionIdleEvent / SessionErrorEvent.
        throw new NotImplementedException("CopilotChatClient.GetStreamingResponseAsync is not implemented yet.");
    }

    public object? GetService(Type serviceType, object? serviceKey = null)
    {
        ArgumentNullException.ThrowIfNull(serviceType);

        if (serviceKey is not null)
        {
            return null;
        }

        // TODO(chat-client agent): return the current CopilotSession inside a call, and ChatClientMetadata.
        if (serviceType == typeof(CopilotClient))
        {
            return clientProvider.Client;
        }

        return serviceType.IsInstanceOfType(this) ? this : null;
    }

    public void Dispose()
    {
    }
}
