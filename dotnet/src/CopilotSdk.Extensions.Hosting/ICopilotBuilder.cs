using Microsoft.Extensions.DependencyInjection;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Configures a Copilot agent registered with <c>AddCopilot</c>.
/// </summary>
public interface ICopilotBuilder
{
    /// <summary>The service collection the agent is registered in.</summary>
    IServiceCollection Services { get; }

    /// <summary>The agent name, or <see langword="null"/> for the default (unkeyed) agent.</summary>
    string? Name { get; }

    /// <summary>
    /// Adds the public methods of <typeparamref name="T"/> that have a
    /// <see cref="System.ComponentModel.DescriptionAttribute"/> as tools. <typeparamref name="T"/> is
    /// registered as scoped if it isn't already registered, and is resolved from a new scope per tool call.
    /// </summary>
    /// <typeparam name="T">The tool class.</typeparam>
    /// <returns>This builder.</returns>
    ICopilotBuilder WithTools<T>() where T : class;

    /// <summary>
    /// Adds a delegate as a tool. Parameters that are registered services are injected from the call's scope.
    /// </summary>
    /// <param name="function">The tool implementation.</param>
    /// <param name="name">The tool name. Defaults to the snake_case method name.</param>
    /// <param name="description">The tool description shown to the model.</param>
    /// <returns>This builder.</returns>
    ICopilotBuilder WithTool(Delegate function, string? name = null, string? description = null);
}
