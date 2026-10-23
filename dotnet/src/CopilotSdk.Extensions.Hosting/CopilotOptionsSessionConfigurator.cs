using GitHub.Copilot;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Applies <see cref="CopilotOptions"/> values (model, instructions, reasoning effort, tool search, BYOK provider, MCP servers)
/// to the session config.
/// </summary>
internal sealed class CopilotOptionsSessionConfigurator(IOptionsMonitor<CopilotOptions> options) : ICopilotSessionConfigurator
{
    public void Configure(string agentName, SessionConfigBase config, IServiceProvider services)
    {
        var o = options.Get(agentName);

        if (o.Model is not null)
        {
            config.Model = o.Model;
        }

        if (o.ReasoningEffort is not null)
        {
            config.ReasoningEffort = o.ReasoningEffort;
        }

        if (!string.IsNullOrWhiteSpace(o.Instructions))
        {
            config.SystemMessage = new SystemMessageConfig { Mode = SystemMessageMode.Append, Content = o.Instructions };
        }

        if (o.ToolSearch is { } toolSearch && (toolSearch.Enabled is not null || toolSearch.DeferThreshold is not null))
        {
            config.ToolSearch = new ToolSearchConfig { Enabled = toolSearch.Enabled, DeferThreshold = toolSearch.DeferThreshold };
        }

        if (o.Provider is not null)
        {
            config.Provider = o.Provider;
        }

        if (o.McpServers.Count > 0)
        {
            var servers = config.McpServers ?? new Dictionary<string, McpServerConfig>(StringComparer.OrdinalIgnoreCase);
            foreach (var (name, server) in o.McpServers)
            {
                if (server is not null && CopilotMcpServerMapper.Map(server) is { } mapped)
                {
                    servers[name] = mapped;
                }
            }

            config.McpServers = servers;
        }
    }
}
