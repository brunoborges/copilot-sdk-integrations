using CopilotSdk.Aspire;
using CopilotSdk.Extensions.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using OpenTelemetry.Metrics;
using OpenTelemetry.Trace;

namespace Microsoft.Extensions.Hosting;

/// <summary>
/// Connects a service to a Copilot runtime or model resource from an Aspire AppHost.
/// </summary>
public static class AspireCopilotExtensions
{
    internal const string DefaultConfigSectionName = "Aspire:Copilot:Client";
    internal const string TelemetrySourceName = "Experimental.Microsoft.Extensions.AI";

    /// <summary>
    /// Registers the default Copilot agent (<see cref="Microsoft.Extensions.AI.IChatClient"/>) connected through
    /// <c>ConnectionStrings:{connectionName}</c>, with a health check and OpenTelemetry tracing and metrics.
    /// </summary>
    /// <param name="builder">The host application builder.</param>
    /// <param name="connectionName">The connection name: the runtime or model resource name in the AppHost.</param>
    /// <param name="configure">Optional code defaults for the agent. Configuration in <c>Copilot</c> overrides them.</param>
    /// <param name="configureSettings">Optional overrides for the Aspire settings.</param>
    /// <returns>The core builder, to add tools.</returns>
    public static ICopilotBuilder AddCopilotClient(
        this IHostApplicationBuilder builder,
        string connectionName,
        Action<CopilotOptions>? configure = null,
        Action<CopilotClientSettings>? configureSettings = null)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentException.ThrowIfNullOrWhiteSpace(connectionName);

        var copilot = builder.Services.AddCopilot(options =>
        {
            options.ConnectionName = connectionName;
            configure?.Invoke(options);
        });

        AddAspireDefaults(builder, Microsoft.Extensions.Options.Options.DefaultName, connectionName, configureSettings);
        return copilot;
    }

    /// <summary>
    /// Registers a keyed Copilot agent (<see cref="Microsoft.Extensions.AI.IChatClient"/> with key
    /// <paramref name="connectionName"/>) connected through <c>ConnectionStrings:{connectionName}</c>, with a health
    /// check and OpenTelemetry tracing and metrics.
    /// </summary>
    /// <param name="builder">The host application builder.</param>
    /// <param name="connectionName">The connection name, also the service key and core agent name.</param>
    /// <param name="configure">Optional code defaults for the agent. Configuration in <c>Copilot:Agents:{connectionName}</c> overrides them.</param>
    /// <param name="configureSettings">Optional overrides for the Aspire settings.</param>
    /// <returns>The core builder, to add tools.</returns>
    /// <remarks>
    /// All agents share one <c>CopilotClient</c>, configured from the default agent. When the default agent has no
    /// endpoint, it takes the runtime endpoint from this connection string, so keyed agents work without a default one.
    /// Keyed agents on different runtimes are not supported.
    /// </remarks>
    public static ICopilotBuilder AddKeyedCopilotClient(
        this IHostApplicationBuilder builder,
        string connectionName,
        Action<CopilotOptions>? configure = null,
        Action<CopilotClientSettings>? configureSettings = null)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentException.ThrowIfNullOrWhiteSpace(connectionName);

        var copilot = builder.Services.AddCopilot(connectionName, options =>
        {
            options.ConnectionName = connectionName;
            configure?.Invoke(options);
        });

        // The shared client reads the default agent's endpoint.
        var configuration = builder.Configuration;
        builder.Services.PostConfigure<CopilotOptions>(Microsoft.Extensions.Options.Options.DefaultName, options =>
        {
            if (options.Endpoint is null
                && CopilotConnectionString.TryParse(configuration.GetConnectionString(connectionName), out var info)
                && info.Endpoint is not null)
            {
                options.Endpoint = info.Endpoint;
                options.ConnectionToken ??= info.ConnectionToken;
            }
        });

        AddAspireDefaults(builder, connectionName, connectionName, configureSettings);
        return copilot;
    }

    private static void AddAspireDefaults(IHostApplicationBuilder builder, string optionsName, string connectionName, Action<CopilotClientSettings>? configureSettings)
    {
        var settings = new CopilotClientSettings();
        builder.Configuration.GetSection(DefaultConfigSectionName).Bind(settings);
        builder.Configuration.GetSection($"{DefaultConfigSectionName}:{connectionName}").Bind(settings);
        configureSettings?.Invoke(settings);

        builder.Services.AddSingleton<IValidateOptions<CopilotOptions>>(
            new CopilotConnectionStringValidator(optionsName, connectionName, builder.Configuration));

        var registry = GetRegistry(builder.Services);
        var healthCheckName = $"copilot_{connectionName}";
        if (!settings.DisableHealthChecks && registry.HealthChecks.Add(healthCheckName))
        {
            builder.Services.AddHealthChecks().AddCopilot(healthCheckName, tags: ["ready"], timeout: settings.HealthCheckTimeout);
        }

        if (!settings.DisableTracing && !registry.Tracing)
        {
            registry.Tracing = true;
            builder.Services.AddOpenTelemetry().WithTracing(tracing => tracing.AddSource(TelemetrySourceName));
        }

        if (!settings.DisableMetrics && !registry.Metrics)
        {
            registry.Metrics = true;
            builder.Services.AddOpenTelemetry().WithMetrics(metrics => metrics.AddMeter(TelemetrySourceName));
        }
    }

    private static AspireCopilotRegistry GetRegistry(IServiceCollection services)
    {
        if (services.FirstOrDefault(d => d.ServiceType == typeof(AspireCopilotRegistry))?.ImplementationInstance is AspireCopilotRegistry registry)
        {
            return registry;
        }

        registry = new AspireCopilotRegistry();
        services.AddSingleton(registry);
        return registry;
    }

    private sealed class AspireCopilotRegistry
    {
        public HashSet<string> HealthChecks { get; } = new(StringComparer.OrdinalIgnoreCase);

        public bool Tracing { get; set; }

        public bool Metrics { get; set; }
    }
}
