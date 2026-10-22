using GitHub.Copilot;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Adds the agent's app tools (<see cref="CopilotToolRegistry"/>) to the session config and sets
/// <see cref="SessionConfigBase.AvailableTools"/> so only app and MCP tools are offered (no built-in tools).
/// </summary>
/// <remarks>
/// <see cref="SessionConfigBase.AvailableTools"/> is always set, which <see cref="CopilotClientMode.Empty"/> requires.
/// App tools are listed by name (<c>custom:{name}</c>); MCP tools are allowed with <c>mcp:*</c> when any of the
/// agent's servers lists tools, because the runtime already limits each server to its
/// <see cref="CopilotMcpServerOptions.Tools"/> and the default permission handler checks them again.
/// Apps can change either list with <see cref="CopilotOptions.ConfigureSession"/>, which runs after this configurator.
/// </remarks>
internal sealed class CopilotToolsSessionConfigurator(CopilotToolCatalog catalog, IOptionsMonitor<CopilotOptions> options) : ICopilotSessionConfigurator
{
    public void Configure(string agentName, SessionConfigBase config, IServiceProvider services)
    {
        var tools = catalog.GetTools(agentName);

        if (tools.Functions.Count > 0)
        {
            var sessionTools = config.Tools is null ? new List<AIFunctionDeclaration>() : [.. config.Tools];
            sessionTools.AddRange(tools.Functions);
            config.Tools = sessionTools;
        }

        config.AvailableTools = GetAvailableTools(tools, options.Get(agentName));
    }

    internal static List<string> GetAvailableTools(CopilotAgentTools tools, CopilotOptions options)
    {
        var available = new List<string>(tools.Functions.Count + 1);
        foreach (var function in tools.Functions)
        {
            available.Add($"custom:{function.Name}");
        }

        if (options.McpServers.Values.Any(server => server?.Tools is { Count: > 0 }))
        {
            available.Add("mcp:*");
        }

        return available;
    }
}
