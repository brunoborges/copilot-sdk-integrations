using CopilotSdk.Extensions.Hosting;
using GitHub.Copilot;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;

namespace Microsoft.Extensions.DependencyInjection;

/// <summary>
/// Registers GitHub Copilot agents as <see cref="IChatClient"/> services.
/// </summary>
public static class CopilotServiceCollectionExtensions
{
    /// <summary>
    /// Registers the default Copilot agent as <see cref="IChatClient"/>, plus the shared <see cref="CopilotClient"/>,
    /// its lifecycle, and the default permission handler. Settings bind from the <c>Copilot</c> configuration section.
    /// Calling it again is safe and adds to the existing registration.
    /// </summary>
    /// <param name="services">The service collection.</param>
    /// <param name="configure">Optional code defaults. Configuration overrides them.</param>
    /// <returns>A builder to add tools.</returns>
    public static ICopilotBuilder AddCopilot(this IServiceCollection services, Action<CopilotOptions>? configure = null)
    {
        ArgumentNullException.ThrowIfNull(services);

        AddCopilotCore(services, Options.Options.DefaultName, configure);
        services.TryAddSingleton<IChatClient>(sp => CopilotChatClientFactory.Create(sp, Options.Options.DefaultName));

        return new CopilotBuilder(services, name: null);
    }

    /// <summary>
    /// Registers a named Copilot agent as a keyed <see cref="IChatClient"/> with key <paramref name="name"/>.
    /// Named agents start from the default agent's settings and override what they set; they bind from
    /// <c>Copilot:Agents:{name}</c>. Every agent shares one <see cref="CopilotClient"/>.
    /// </summary>
    /// <param name="services">The service collection.</param>
    /// <param name="name">The agent name and service key.</param>
    /// <param name="configure">Optional code defaults. Configuration overrides them.</param>
    /// <returns>A builder to add tools.</returns>
    public static ICopilotBuilder AddCopilot(this IServiceCollection services, string name, Action<CopilotOptions>? configure = null)
    {
        ArgumentNullException.ThrowIfNull(services);
        ArgumentException.ThrowIfNullOrWhiteSpace(name);

        AddCopilotCore(services, name, configure);
        services.TryAddKeyedSingleton<IChatClient>(name, static (sp, key) => CopilotChatClientFactory.Create(sp, (string)key!));

        return new CopilotBuilder(services, name);
    }

    private static void AddCopilotCore(IServiceCollection services, string agentName, Action<CopilotOptions>? configure)
    {
        CopilotAgentRegistry.GetOrAdd(services).Add(agentName, configure);
        CopilotToolRegistry.GetOrAdd(services);

        services.AddOptions<CopilotOptions>(agentName).ValidateOnStart();

        // Shared registrations, added once.
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IConfigureOptions<CopilotOptions>, CopilotOptionsSetup>());
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IValidateOptions<CopilotOptions>, CopilotOptionsValidator>());

        services.TryAddSingleton<ICopilotClientProvider, CopilotClientProvider>();
        services.TryAddSingleton(static sp => sp.GetRequiredService<ICopilotClientProvider>().Client);
        services.TryAddEnumerable(ServiceDescriptor.Singleton<IHostedService, CopilotClientLifetime>());
        services.TryAddEnumerable(ServiceDescriptor.Singleton<ICopilotClientConfigurator, CopilotClientOptionsConfigurator>());

        services.TryAddSingleton<CopilotSessionConfigFactory>();
        services.TryAddEnumerable(ServiceDescriptor.Singleton<ICopilotSessionConfigurator, CopilotOptionsSessionConfigurator>());
        services.TryAddEnumerable(ServiceDescriptor.Singleton<ICopilotSessionConfigurator, CopilotToolsSessionConfigurator>());
        services.TryAddEnumerable(ServiceDescriptor.Singleton<ICopilotSessionConfigurator, CopilotPermissionsSessionConfigurator>());
    }
}
