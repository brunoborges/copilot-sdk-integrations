using GitHub.Copilot;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Contributes settings to the SDK session config for an agent, for both new
/// (<see cref="SessionConfig"/>) and resumed (<see cref="ResumeSessionConfig"/>) sessions.
/// </summary>
/// <remarks>
/// Register with <c>services.TryAddEnumerable(ServiceDescriptor.Singleton&lt;ICopilotSessionConfigurator, T&gt;())</c>.
/// <see cref="CopilotSessionConfigFactory"/> runs all configurators in registration order, then the app's
/// <see cref="CopilotOptions.ConfigureSession"/> actions last, so app code can override anything set here.
/// </remarks>
internal interface ICopilotSessionConfigurator
{
    /// <param name="agentName">The agent name (<see cref="Microsoft.Extensions.Options.Options.DefaultName"/> for the default agent).</param>
    /// <param name="config">The session config being built.</param>
    /// <param name="services">The service provider of the current call.</param>
    void Configure(string agentName, SessionConfigBase config, IServiceProvider services);
}
