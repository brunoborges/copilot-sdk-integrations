using GitHub.Copilot;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Contributes settings to the shared <see cref="CopilotClientOptions"/> before the <see cref="CopilotClient"/> is created.
/// </summary>
/// <remarks>
/// Register with <c>services.TryAddEnumerable(ServiceDescriptor.Singleton&lt;ICopilotClientConfigurator, T&gt;())</c>.
/// <see cref="CopilotClientProvider"/> runs all configurators in registration order, then the default agent's
/// <see cref="CopilotOptions.ConfigureClient"/> actions last.
/// </remarks>
internal interface ICopilotClientConfigurator
{
    /// <param name="options">The client options being built.</param>
    /// <param name="copilotOptions">The default agent's options.</param>
    void Configure(CopilotClientOptions options, CopilotOptions copilotOptions);
}
