using Aspire.Hosting.ApplicationModel;
using GitHub.Copilot;

namespace CopilotSdk.Aspire.Hosting;

/// <summary>
/// A GitHub Copilot runtime (<c>copilot-runtime --headless</c>) that services connect to over TCP.
/// </summary>
/// <remarks>
/// <para>
/// By default the runtime runs as an executable: the <c>copilot-runtime</c> bundled with <c>GitHub.Copilot.SDK</c>
/// in the AppHost output. <c>RunAsContainer</c> (and publish mode) swap it for a <see cref="CopilotContainerResource"/>
/// with the same name, and <c>AsExisting</c> swaps it for a connection string. Every mode exposes the same
/// <see cref="ConnectionStringExpression"/>: <c>Endpoint=tcp://{host}:{port};ConnectionToken={token}</c>.
/// </para>
/// <para>
/// When the runtime is swapped, the resource delegates its annotations to the resource that actually runs, so
/// builder calls made before or after the swap (<c>WithEnvironment</c>, <c>WaitFor</c>, health checks, ...) apply to it.
/// </para>
/// </remarks>
public sealed class CopilotRuntimeResource : ExecutableResource, IResourceWithConnectionString
{
    /// <summary>The name of the runtime's TCP endpoint.</summary>
    public const string PrimaryEndpointName = "tcp";

    private ReferenceExpression? _existingConnectionString;

    /// <summary>
    /// Creates a runtime resource.
    /// </summary>
    /// <param name="name">The resource name.</param>
    /// <param name="command">The runtime executable.</param>
    /// <param name="workingDirectory">The working directory of the executable.</param>
    /// <param name="connectionToken">The shared secret clients present to the runtime.</param>
    public CopilotRuntimeResource(string name, string command, string workingDirectory, ParameterResource connectionToken)
        : base(name, command, workingDirectory)
    {
        ArgumentNullException.ThrowIfNull(connectionToken);
        ConnectionTokenParameter = connectionToken;
    }

    /// <inheritdoc />
    public override ResourceAnnotationCollection Annotations => InnerResource?.Annotations ?? base.Annotations;

    /// <summary>The runtime's TCP endpoint.</summary>
    public EndpointReference PrimaryEndpoint => InnerResource is IResourceWithEndpoints inner
        ? new EndpointReference(inner, PrimaryEndpointName)
        : new EndpointReference(this, PrimaryEndpointName);

    /// <summary>The shared secret clients present to the runtime (<c>COPILOT_CONNECTION_TOKEN</c>).</summary>
    public ParameterResource ConnectionTokenParameter { get; internal set; }

    /// <summary>The GitHub token the runtime signs in with (<c>COPILOT_GITHUB_TOKEN</c>), if any.</summary>
    public ParameterResource? GitHubTokenParameter { get; internal set; }

    /// <summary>
    /// The resource that runs in place of this one: a <see cref="CopilotContainerResource"/> after
    /// <c>RunAsContainer</c> or in publish mode, a connection string resource after <c>AsExisting</c>, otherwise
    /// <see langword="null"/> (this executable runs).
    /// </summary>
    public IResource? InnerResource { get; private set; }

    /// <summary>The container that runs the runtime, when running as a container.</summary>
    public CopilotContainerResource? Container => InnerResource as CopilotContainerResource;

    /// <summary>Whether this resource points at a runtime that Aspire does not start.</summary>
    public bool IsExisting => _existingConnectionString is not null;

    /// <summary>The <c>COPILOT_HOME</c> directory used in executable mode; <see langword="null"/> for the default.</summary>
    public string? HomeDirectory { get; internal set; }

    /// <summary>The runtime log level (<c>--log-level</c>), if set.</summary>
    public CopilotLogLevel? LogLevel { get; internal set; }

    /// <summary>Whether prompts and responses are recorded in telemetry.</summary>
    public bool CaptureMessageContent { get; internal set; }

    /// <summary>The models served by this runtime.</summary>
    public IReadOnlyList<CopilotModelResource> Models => ModelList;

    internal List<CopilotModelResource> ModelList { get; } = [];

    /// <summary>The automatically created GitHub token parameter (container and publish modes), if any.</summary>
    internal ParameterResource? DefaultGitHubTokenParameter { get; set; }

    /// <summary>
    /// The connection string: <c>Endpoint=tcp://{host}:{port};ConnectionToken={token}</c>.
    /// </summary>
    public ReferenceExpression ConnectionStringExpression =>
        _existingConnectionString ??
        ReferenceExpression.Create($"Endpoint=tcp://{PrimaryEndpoint.Property(EndpointProperty.HostAndPort)};ConnectionToken={ConnectionTokenParameter}");

    IEnumerable<KeyValuePair<string, ReferenceExpression>> IResourceWithConnectionString.GetConnectionProperties()
    {
        if (_existingConnectionString is not null)
        {
            yield break;
        }

        yield return new("Host", ReferenceExpression.Create($"{PrimaryEndpoint.Property(EndpointProperty.Host)}"));
        yield return new("Port", ReferenceExpression.Create($"{PrimaryEndpoint.Property(EndpointProperty.Port)}"));
        yield return new("Endpoint", ReferenceExpression.Create($"tcp://{PrimaryEndpoint.Property(EndpointProperty.HostAndPort)}"));
        yield return new("ConnectionToken", ReferenceExpression.Create($"{ConnectionTokenParameter}"));
    }

    internal void SetInnerResource(IResource inner, ReferenceExpression? existingConnectionString = null)
    {
        foreach (var annotation in Annotations.ToArray())
        {
            // Skip what defines how the current resource runs; the inner resource brings its own.
            // A container gets its own endpoint (fixed target port); an existing runtime has none.
            if (annotation is ExecutableAnnotation or ContainerImageAnnotation or DockerfileBuildAnnotation
                or EndpointAnnotation { Name: PrimaryEndpointName }
                || annotation.GetType().Name == "ExecutableLaunchRecipeAnnotation")
            {
                continue;
            }

            inner.Annotations.Add(annotation);
        }

        InnerResource = inner;
        _existingConnectionString = existingConnectionString;
    }
}
