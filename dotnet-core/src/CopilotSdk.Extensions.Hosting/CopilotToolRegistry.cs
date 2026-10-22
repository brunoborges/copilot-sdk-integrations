using Microsoft.Extensions.DependencyInjection;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// A tool registered with <see cref="ICopilotBuilder.WithTools{T}"/> or <see cref="ICopilotBuilder.WithTool"/>.
/// Exactly one of <see cref="ToolType"/> and <see cref="Function"/> is set.
/// </summary>
/// <param name="AgentName">The agent the tool belongs to (<see cref="Microsoft.Extensions.Options.Options.DefaultName"/> for the default agent).</param>
/// <param name="ToolType">The tool class for <c>WithTools&lt;T&gt;()</c>.</param>
/// <param name="Function">The delegate for <c>WithTool(delegate)</c>.</param>
/// <param name="Name">Optional tool name for <c>WithTool</c>.</param>
/// <param name="Description">Optional tool description for <c>WithTool</c>.</param>
internal sealed record CopilotToolRegistration(
    string AgentName,
    Type? ToolType,
    Delegate? Function,
    string? Name,
    string? Description);

/// <summary>
/// Per-agent list of tool registrations. One instance per service collection, registered as a singleton instance.
/// </summary>
/// <remarks>
/// Registration only records what the app asked for. Building <c>AIFunction</c>s (DI scope per call,
/// <c>ExecutionContext</c> flow, snake_case names, <c>SkipPermission</c>, <see cref="RequiresApprovalAttribute"/>)
/// happens in <see cref="CopilotToolFactory"/> (cached per agent by <see cref="CopilotToolCatalog"/>).
/// </remarks>
internal sealed class CopilotToolRegistry
{
    private readonly List<CopilotToolRegistration> _registrations = [];

    public IReadOnlyList<CopilotToolRegistration> Registrations => _registrations;

    public void Add(CopilotToolRegistration registration)
    {
        ArgumentNullException.ThrowIfNull(registration);

        if (registration.ToolType is { } toolType && !CopilotToolFactory.GetToolMethods(toolType).Any())
        {
            throw new ArgumentException(
                $"'{toolType}' has no public methods marked with [Description]; WithTools<T>() exposes only those methods as tools.",
                nameof(registration));
        }

        if (registration.Function is { } function &&
            string.IsNullOrWhiteSpace(registration.Name) &&
            CopilotToolFactory.GetToolName(function.Method) is null)
        {
            throw new ArgumentException(
                "A tool name is required for a lambda or anonymous delegate. Pass a name to WithTool or use a named method.",
                nameof(registration));
        }

        if (registration.ToolType is not null &&
            _registrations.Exists(r => r.AgentName == registration.AgentName && r.ToolType == registration.ToolType))
        {
            return;
        }

        _registrations.Add(registration);
    }

    /// <summary>The tools registered for <paramref name="agentName"/>, in registration order.</summary>
    public IEnumerable<CopilotToolRegistration> GetTools(string agentName) =>
        _registrations.Where(r => string.Equals(r.AgentName, agentName, StringComparison.Ordinal));

    public static CopilotToolRegistry GetOrAdd(IServiceCollection services) => services.GetOrAddSingletonInstance<CopilotToolRegistry>();
}
