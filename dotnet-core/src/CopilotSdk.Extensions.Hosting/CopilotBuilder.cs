using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

internal sealed class CopilotBuilder(IServiceCollection services, string? name) : ICopilotBuilder
{
    public IServiceCollection Services { get; } = services;

    public string? Name { get; } = name;

    private string AgentName => Name ?? Options.DefaultName;

    public ICopilotBuilder WithTools<T>() where T : class
    {
        Services.TryAddScoped<T>();
        CopilotToolRegistry.GetOrAdd(Services).Add(new CopilotToolRegistration(AgentName, typeof(T), null, null, null));
        return this;
    }

    public ICopilotBuilder WithTool(Delegate function, string? name = null, string? description = null)
    {
        ArgumentNullException.ThrowIfNull(function);
        CopilotToolRegistry.GetOrAdd(Services).Add(new CopilotToolRegistration(AgentName, null, function, name, description));
        return this;
    }
}
