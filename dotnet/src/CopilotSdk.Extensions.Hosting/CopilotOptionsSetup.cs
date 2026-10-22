using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Composes <see cref="CopilotOptions"/> for an agent. Configuration overrides code.
/// </summary>
/// <remarks>
/// <list type="number">
/// <item>Default agent: its code actions, then the <c>Copilot</c> section.</item>
/// <item>Named agent <c>N</c>: the default agent's code actions and the <c>Copilot</c> section, then <c>N</c>'s code
/// actions, then <c>Copilot:Agents:N</c>. Because both run on the same instance, a named agent inherits the default
/// agent's delegates, <see cref="CopilotOptions.McpServers"/>, and <see cref="CopilotOptions.ConfigureSession"/> actions,
/// and adds its own.</item>
/// <item>Finally, <c>ConnectionStrings:{ConnectionName}</c> fills values that are still unset
/// (see <see cref="CopilotConnectionInfo.ApplyTo"/>).</item>
/// </list>
/// </remarks>
internal sealed class CopilotOptionsSetup(CopilotAgentRegistry registry, IServiceProvider services) : IConfigureNamedOptions<CopilotOptions>
{
    internal const string AgentsSectionName = "Agents";

    public void Configure(CopilotOptions options) => Configure(Options.DefaultName, options);

    public void Configure(string? name, CopilotOptions options)
    {
        name ??= Options.DefaultName;
        var configuration = services.GetService<IConfiguration>();
        var section = configuration?.GetSection(CopilotOptions.SectionName);

        Apply(registry.GetConfigureActions(Options.DefaultName), options);
        section?.Bind(options);

        if (name != Options.DefaultName)
        {
            Apply(registry.GetConfigureActions(name), options);
            section?.GetSection(AgentsSectionName).GetSection(name).Bind(options);
        }

        if (configuration is not null)
        {
            ApplyConnectionString(configuration, options);
        }
    }

    private void ApplyConnectionString(IConfiguration configuration, CopilotOptions options)
    {
        if (string.IsNullOrWhiteSpace(options.ConnectionName))
        {
            return;
        }

        var connectionString = configuration.GetConnectionString(options.ConnectionName);
        if (string.IsNullOrWhiteSpace(connectionString))
        {
            return;
        }

        if (!CopilotConnectionString.TryParse(connectionString, out var info))
        {
            throw new InvalidOperationException(
                $"ConnectionStrings:{options.ConnectionName} is not a valid Copilot connection string. " +
                "Expected 'Endpoint=tcp://host:port;ConnectionToken=...;Model=...'.");
        }

        if (info.UnknownKeys.Count > 0)
        {
            services.GetService<ILoggerFactory>()?.CreateLogger<CopilotOptions>().LogWarning(
                "Ignoring unknown keys in connection string '{ConnectionName}': {Keys}.",
                options.ConnectionName,
                string.Join(", ", info.UnknownKeys));
        }

        info.ApplyTo(options);
    }

    private static void Apply(IReadOnlyList<Action<CopilotOptions>> actions, CopilotOptions options)
    {
        foreach (var action in actions)
        {
            action(options);
        }
    }
}
