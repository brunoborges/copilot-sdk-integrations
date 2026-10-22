using Microsoft.Extensions.AI;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Creates the <see cref="IChatClient"/> registered for an agent (unkeyed for the default agent, keyed by name
/// for named agents): the <see cref="CopilotChatClient"/> wrapped with Microsoft.Extensions.AI OpenTelemetry
/// (GenAI semantic conventions, source <c>Experimental.Microsoft.Extensions.AI</c>). Sensitive data (message
/// content) is not recorded. <c>GetService</c> calls are forwarded through the pipeline, so
/// <c>GetService&lt;CopilotChatClient&gt;()</c> and <c>GetService&lt;CopilotClient&gt;()</c> keep working.
/// </summary>
internal static class CopilotChatClientFactory
{
    public static IChatClient Create(IServiceProvider services, string agentName)
    {
        IChatClient client = ActivatorUtilities.CreateInstance<CopilotChatClient>(services, agentName);

        return client
            .AsBuilder()
            .UseOpenTelemetry(services.GetService<ILoggerFactory>(), configure: static c => c.EnableSensitiveData = false)
            .Build(services);
    }
}
