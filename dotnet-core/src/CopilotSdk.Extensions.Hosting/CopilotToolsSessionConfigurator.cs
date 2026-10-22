using GitHub.Copilot;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Adds the agent's registered tools (<see cref="CopilotToolRegistry"/>) to the session config.
/// </summary>
internal sealed class CopilotToolsSessionConfigurator(CopilotToolRegistry registry) : ICopilotSessionConfigurator
{
    public void Configure(string agentName, SessionConfigBase config, IServiceProvider services)
    {
        // TODO(tools agent): build AIFunctions for registry.GetTools(agentName) (cache per agent) and add them to
        // config.Tools: snake_case names, DI scope per call, caller ExecutionContext flow, SkipPermission unless
        // [RequiresApproval], generic error results. Also set config.AvailableTools for client Mode = Empty.
        _ = registry.GetTools(agentName);
    }
}
