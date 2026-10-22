using Microsoft.Extensions.DependencyInjection;

namespace CopilotSdk.Extensions.Hosting;

internal static class CopilotServiceCollectionInternalExtensions
{
    /// <summary>Returns the singleton instance of <typeparamref name="T"/> registered in <paramref name="services"/>, adding one if needed.</summary>
    public static T GetOrAddSingletonInstance<T>(this IServiceCollection services) where T : class, new()
    {
        foreach (var descriptor in services)
        {
            if (descriptor.ServiceType == typeof(T) && !descriptor.IsKeyedService && descriptor.ImplementationInstance is T existing)
            {
                return existing;
            }
        }

        var instance = new T();
        services.AddSingleton(instance);
        return instance;
    }
}
