using Microsoft.Copilot;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Builds SDK session options for an agent: every <see cref="ICopilotSessionConfigurator"/> in
/// registration order, then the agent's <see cref="CopilotOptions.ConfigureSession"/> actions.
/// </summary>
internal sealed class CopilotSessionConfigFactory(
    IEnumerable<ICopilotSessionConfigurator> configurators,
    IOptionsMonitor<CopilotOptions> options)
{
    private readonly ICopilotSessionConfigurator[] _configurators = [.. configurators];

    public CreateSessionOptions CreateSessionConfig(string agentName, IServiceProvider services) =>
        Apply(agentName, new CreateSessionOptions(), services);

    public ResumeSessionOptions CreateResumeSessionConfig(string agentName, IServiceProvider services) =>
        Apply(agentName, new ResumeSessionOptions(), services);

    private T Apply<T>(string agentName, T config, IServiceProvider services) where T : SessionOptionsBase
    {
        foreach (var configurator in _configurators)
        {
            configurator.Configure(agentName, config, services);
        }

        foreach (var configure in options.Get(agentName).SessionConfigurations)
        {
            configure(config);
        }

        return config;
    }
}
