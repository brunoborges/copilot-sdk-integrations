using System.Collections.Concurrent;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.Logging;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>The app tools of one agent.</summary>
internal sealed class CopilotAgentTools
{
    public CopilotAgentTools(IReadOnlyList<CopilotToolDefinition> tools)
    {
        Tools = tools;
        Functions = [.. tools.Select(t => t.Function)];
        ToolNames = tools.Select(t => t.Name).ToHashSet(StringComparer.Ordinal);
        ApprovalRequired = tools.Where(t => t.RequiresApproval).Select(t => t.Name).ToHashSet(StringComparer.Ordinal);
    }

    public IReadOnlyList<CopilotToolDefinition> Tools { get; }

    public IReadOnlyList<AIFunction> Functions { get; }

    /// <summary>Names of every app tool.</summary>
    public IReadOnlySet<string> ToolNames { get; }

    /// <summary>Names of the tools marked with <see cref="RequiresApprovalAttribute"/>.</summary>
    public IReadOnlySet<string> ApprovalRequired { get; }
}

/// <summary>
/// Builds and caches each agent's tools. The functions are stateless (they resolve services per call), so one
/// set per agent serves every session.
/// </summary>
internal sealed class CopilotToolCatalog(
    CopilotToolRegistry registry,
    IServiceProvider services,
    CopilotTurnContextRegistry? turns = null,
    ILoggerFactory? loggerFactory = null)
{
    private readonly CopilotToolFactory _factory = new(services, turns, loggerFactory);
    private readonly ConcurrentDictionary<string, Lazy<CopilotAgentTools>> _agents = new(StringComparer.Ordinal);

    public CopilotAgentTools GetTools(string agentName) =>
        _agents.GetOrAdd(agentName, name => new Lazy<CopilotAgentTools>(() => Build(name))).Value;

    private CopilotAgentTools Build(string agentName)
    {
        var tools = new List<CopilotToolDefinition>();
        var names = new HashSet<string>(StringComparer.Ordinal);

        foreach (var registration in registry.GetTools(agentName))
        {
            foreach (var tool in _factory.Create(registration))
            {
                if (!names.Add(tool.Name))
                {
                    var agent = string.IsNullOrEmpty(agentName) ? "the default agent" : $"agent '{agentName}'";
                    throw new InvalidOperationException(
                        $"More than one tool named '{tool.Name}' is registered for {agent}. Tool names must be unique.");
                }

                tools.Add(tool);
            }
        }

        return new CopilotAgentTools(tools);
    }
}
