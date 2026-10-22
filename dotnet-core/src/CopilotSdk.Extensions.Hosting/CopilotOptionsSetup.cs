using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Composes <see cref="CopilotOptions"/> for an agent. For the default agent: code actions, then the
/// <c>Copilot</c> section. For a named agent: the default agent's code actions and section first, then the
/// named agent's code actions and the <c>Copilot:Agents:{name}</c> section. Configuration overrides code.
/// </summary>
internal sealed class CopilotOptionsSetup(CopilotAgentRegistry registry, IServiceProvider services) : IConfigureNamedOptions<CopilotOptions>
{
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
            section?.GetSection("Agents").GetSection(name).Bind(options);
        }

        // TODO(config agent): overlay ConnectionStrings:{options.ConnectionName}
        // (Endpoint=...;ConnectionToken=...;Model=...) where explicit Copilot:* values are not set.
    }

    private static void Apply(IReadOnlyList<Action<CopilotOptions>> actions, CopilotOptions options)
    {
        foreach (var action in actions)
        {
            action(options);
        }
    }
}
