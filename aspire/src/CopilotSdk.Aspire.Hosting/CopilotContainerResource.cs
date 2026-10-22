using Aspire.Hosting.ApplicationModel;

namespace CopilotSdk.Aspire.Hosting;

/// <summary>
/// The container that runs a <see cref="CopilotRuntimeResource"/> after <c>RunAsContainer</c> and in publish mode.
/// It has the same name as the runtime, so <c>WaitFor</c> and <c>WithReference</c> on the runtime target it.
/// </summary>
public sealed class CopilotContainerResource : ContainerResource, IResourceWithConnectionString
{
    /// <summary>The port the runtime listens on inside the container.</summary>
    public const int RuntimePort = 3000;

    /// <summary>The <c>COPILOT_HOME</c> directory inside the container; data volumes and bind mounts mount here.</summary>
    public const string HomeDirectory = "/home/copilot/.copilot";

    /// <summary>
    /// Creates the container for <paramref name="runtime"/>.
    /// </summary>
    /// <param name="runtime">The runtime this container runs.</param>
    public CopilotContainerResource(CopilotRuntimeResource runtime)
        : base(runtime?.Name ?? throw new ArgumentNullException(nameof(runtime)))
    {
        Runtime = runtime;
    }

    /// <summary>The runtime this container runs.</summary>
    public CopilotRuntimeResource Runtime { get; }

    /// <summary>The runtime's TCP endpoint on this container.</summary>
    public EndpointReference PrimaryEndpoint => new(this, CopilotRuntimeResource.PrimaryEndpointName);

    /// <summary>The runtime's connection string, so the container is a connection string source in published manifests.</summary>
    public ReferenceExpression ConnectionStringExpression => Runtime.ConnectionStringExpression;

    IEnumerable<KeyValuePair<string, ReferenceExpression>> IResourceWithConnectionString.GetConnectionProperties() =>
        ((IResourceWithConnectionString)Runtime).GetConnectionProperties();
}
