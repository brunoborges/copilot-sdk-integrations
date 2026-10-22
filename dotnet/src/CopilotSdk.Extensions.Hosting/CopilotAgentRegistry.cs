using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Records every agent registered with <c>AddCopilot</c> and the configure actions supplied in code.
/// One instance per service collection, registered as a singleton instance.
/// </summary>
/// <remarks>
/// <see cref="CopilotOptionsSetup"/> composes options from these actions so that named agents start
/// from the default agent's settings, and configuration overrides code.
/// </remarks>
internal sealed class CopilotAgentRegistry
{
    private readonly Dictionary<string, List<Action<CopilotOptions>>> _configureActions = new(StringComparer.Ordinal);
    private readonly List<string> _agentNames = [];

    /// <summary>Agent names in registration order. The default agent is <see cref="Options.DefaultName"/>.</summary>
    public IReadOnlyList<string> AgentNames => _agentNames;

    public bool Contains(string agentName) => _configureActions.ContainsKey(agentName);

    public void Add(string agentName, Action<CopilotOptions>? configure)
    {
        if (!_configureActions.TryGetValue(agentName, out var actions))
        {
            actions = [];
            _configureActions.Add(agentName, actions);
            _agentNames.Add(agentName);
        }

        if (configure is not null)
        {
            actions.Add(configure);
        }
    }

    /// <summary>The code configure actions for <paramref name="agentName"/>, in registration order.</summary>
    public IReadOnlyList<Action<CopilotOptions>> GetConfigureActions(string agentName) =>
        _configureActions.TryGetValue(agentName, out var actions) ? actions : [];

    public static CopilotAgentRegistry GetOrAdd(IServiceCollection services) => services.GetOrAddSingletonInstance<CopilotAgentRegistry>();
}
