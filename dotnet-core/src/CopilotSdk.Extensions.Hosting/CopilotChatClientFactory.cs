using Microsoft.Extensions.AI;
using Microsoft.Extensions.DependencyInjection;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Creates the <see cref="IChatClient"/> registered for an agent (unkeyed for the default agent, keyed by name
/// for named agents).
/// </summary>
internal static class CopilotChatClientFactory
{
    public static IChatClient Create(IServiceProvider services, string agentName)
    {
        IChatClient client = ActivatorUtilities.CreateInstance<CopilotChatClient>(services, agentName);

        // TODO(telemetry agent): wrap with the Microsoft.Extensions.AI pipeline, e.g.
        // client.AsBuilder().UseOpenTelemetry(...).UseLogging(...).Build(services).
        return client;
    }
}
