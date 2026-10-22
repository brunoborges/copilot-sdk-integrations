using System.Security.Cryptography;
using System.Text;
using Aspire.Hosting.ApplicationModel;
using CopilotSdk.Aspire.Hosting;
using GitHub.Copilot;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Diagnostics.HealthChecks;

namespace Aspire.Hosting;

/// <summary>
/// Adds GitHub Copilot runtimes and models to an Aspire AppHost.
/// </summary>
public static class CopilotResourceBuilderExtensions
{
    private const string CaptureMessageContentVariable = "OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT";

    /// <summary>
    /// Adds a Copilot runtime. In run mode it starts the <c>copilot-runtime</c> bundled with <c>GitHub.Copilot.SDK</c>
    /// as an executable; in publish mode (and after <see cref="RunAsContainer"/>) it runs as a container.
    /// </summary>
    /// <param name="builder">The distributed application builder.</param>
    /// <param name="name">The resource name, also the connection name for <c>WithReference</c>.</param>
    /// <param name="port">The host port of the runtime's TCP endpoint; random when <see langword="null"/>.</param>
    /// <returns>The runtime resource builder.</returns>
    /// <remarks>
    /// Projects get <c>ConnectionStrings__{name}=Endpoint=tcp://host:port;ConnectionToken=...</c> through
    /// <c>WithReference</c>. A connection token is generated (<c>{name}-connection-token</c>), telemetry is exported
    /// to the dashboard over OTLP/HTTP, and a health check reports healthy once the runtime answers and is signed in.
    /// </remarks>
    public static IResourceBuilder<CopilotRuntimeResource> AddCopilot(this IDistributedApplicationBuilder builder, [ResourceName] string name, int? port = null)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentException.ThrowIfNullOrWhiteSpace(name);

        var connectionToken = ParameterResourceBuilderExtensions.CreateDefaultPasswordParameter(builder, $"{name}-connection-token", special: false);
        var command = CopilotRuntimeLocator.Resolve(AppContext.BaseDirectory, Environment.GetEnvironmentVariable);
        var runtime = new CopilotRuntimeResource(name, command, builder.AppHostDirectory, connectionToken);

        var healthCheckKey = $"{name}_copilot_check";
        builder.Services.AddKeyedSingleton(healthCheckKey, (_, _) => new CopilotRuntimeHealthCheck(runtime));
        builder.Services.AddHealthChecks().Add(new HealthCheckRegistration(
            healthCheckKey,
            sp => sp.GetRequiredKeyedService<CopilotRuntimeHealthCheck>(healthCheckKey),
            failureStatus: default,
            tags: default,
            timeout: TimeSpan.FromSeconds(10)));

        EnsureReferenceWiring(builder).Add(runtime);

        var resourceBuilder = builder.AddResource(runtime)
            .WithEndpoint(port: port, scheme: "tcp", name: CopilotRuntimeResource.PrimaryEndpointName)
            .WithArgs(context => AddArguments(runtime, context))
            .WithOtlpExporter(OtlpProtocol.HttpProtobuf)
            // After WithOtlpExporter, so the capture setting overrides Aspire's development default.
            .WithEnvironment(context => AddEnvironment(runtime, context, builder))
            .WithHealthCheck(healthCheckKey);

        if (builder.ExecutionContext.IsPublishMode)
        {
            resourceBuilder.RunAsContainer();
        }

        return resourceBuilder;
    }

    /// <summary>
    /// Adds a model served by the runtime. Projects that reference it get the runtime connection string plus
    /// <c>;Model={model}</c>.
    /// </summary>
    /// <param name="builder">The runtime resource builder.</param>
    /// <param name="name">The resource name, also the connection name for <c>WithReference</c>.</param>
    /// <param name="model">The model identifier, for example <c>gpt-5.4</c>.</param>
    /// <returns>The model resource builder.</returns>
    public static IResourceBuilder<CopilotModelResource> AddModel(this IResourceBuilder<CopilotRuntimeResource> builder, [ResourceName] string name, string model)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentException.ThrowIfNullOrWhiteSpace(name);
        ArgumentException.ThrowIfNullOrWhiteSpace(model);

        var resource = new CopilotModelResource(name, model, builder.Resource);
        builder.Resource.ModelList.Add(resource);
        return builder.ApplicationBuilder.AddResource(resource);
    }

    /// <summary>
    /// Runs the runtime as a container instead of an executable. Without <c>WithImage</c>, the image is built from a
    /// Dockerfile shipped with this package (Debian slim plus the checksum-verified Copilot CLI release that
    /// <c>GitHub.Copilot.SDK</c> pins).
    /// </summary>
    /// <param name="builder">The runtime resource builder.</param>
    /// <param name="configure">Configures the container, for example <c>WithImage</c> or <c>WithLifetime</c>.</param>
    /// <returns>The runtime resource builder.</returns>
    /// <remarks>
    /// The container needs a GitHub token. Unless <see cref="WithGitHubToken"/> is called, a
    /// <c>{name}-github-token</c> secret parameter is added, which the dashboard prompts for.
    /// </remarks>
    public static IResourceBuilder<CopilotRuntimeResource> RunAsContainer(this IResourceBuilder<CopilotRuntimeResource> builder, Action<IResourceBuilder<CopilotContainerResource>>? configure = null)
    {
        ArgumentNullException.ThrowIfNull(builder);

        var runtime = builder.Resource;
        if (runtime.IsExisting)
        {
            throw new InvalidOperationException($"'{runtime.Name}' points at an existing runtime and cannot run as a container.");
        }

        var appBuilder = builder.ApplicationBuilder;
        if (runtime.Container is { } existingContainer)
        {
            ConfigureContainer(appBuilder.CreateResourceBuilder(existingContainer), configure);
            return builder;
        }

        var port = runtime.Annotations.OfType<EndpointAnnotation>().FirstOrDefault(e => e.Name == CopilotRuntimeResource.PrimaryEndpointName)?.Port;

        var container = new CopilotContainerResource(runtime);
        var contextPath = Path.Combine(appBuilder.AppHostDirectory, "obj", "copilot-runtime");
        var cliVersion = CopilotRuntimeLocator.CliVersion;

        appBuilder.Resources.Remove(runtime);
        runtime.SetInnerResource(container);

        // Like AddDockerfile: the image annotation names the container; the Dockerfile build supplies the image.
        var containerBuilder = appBuilder.AddResource(container)
            .WithAnnotation(new ContainerImageAnnotation { Image = "copilot-runtime", Tag = cliVersion })
            .WithDockerfileFactory(contextPath, _ =>
            {
                Directory.CreateDirectory(contextPath);
                return CopilotDockerfile.Create(cliVersion);
            })
            .WithEndpoint(port: port, targetPort: CopilotContainerResource.RuntimePort, scheme: "tcp", name: CopilotRuntimeResource.PrimaryEndpointName, isExternal: false);

        if (runtime.GitHubTokenParameter is null)
        {
            var parameter = appBuilder.AddParameter($"{runtime.Name}-github-token", secret: true);
            runtime.DefaultGitHubTokenParameter = parameter.Resource;
            runtime.GitHubTokenParameter = parameter.Resource;
        }

        ConfigureContainer(containerBuilder, configure);
        return builder;
    }

    /// <summary>
    /// Points at a runtime that Aspire does not start. Projects get <c>Endpoint={endpoint};ConnectionToken={token}</c>.
    /// </summary>
    /// <param name="builder">The runtime resource builder.</param>
    /// <param name="endpoint">The runtime endpoint, for example <c>tcp://copilot.internal:3000</c>.</param>
    /// <param name="connectionToken">The runtime's connection token.</param>
    /// <returns>The runtime resource builder.</returns>
    public static IResourceBuilder<CopilotRuntimeResource> AsExisting(this IResourceBuilder<CopilotRuntimeResource> builder, IResourceBuilder<ParameterResource> endpoint, IResourceBuilder<ParameterResource> connectionToken)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentNullException.ThrowIfNull(endpoint);
        ArgumentNullException.ThrowIfNull(connectionToken);

        var runtime = builder.Resource;
        var appBuilder = builder.ApplicationBuilder;
        var expression = ReferenceExpression.Create($"Endpoint={endpoint.Resource};ConnectionToken={connectionToken.Resource}");

        if (runtime.InnerResource is { } inner)
        {
            appBuilder.Resources.Remove(inner);
        }
        else
        {
            appBuilder.Resources.Remove(runtime);
        }

        RemoveDefaultGitHubToken(runtime, appBuilder);
        runtime.ConnectionTokenParameter = connectionToken.Resource;

        var connectionString = new ConnectionStringResource(runtime.Name, expression);
        runtime.SetInnerResource(connectionString, expression);
        appBuilder.AddResource(connectionString);
        return builder;
    }

    /// <summary>
    /// Sets the runtime executable for executable mode (default: <c>COPILOT_CLI_PATH</c>, then the runtime bundled
    /// with <c>GitHub.Copilot.SDK</c>, then <c>copilot</c> on <c>PATH</c>).
    /// </summary>
    /// <param name="builder">The runtime resource builder.</param>
    /// <param name="path">The executable path; relative paths resolve against the AppHost directory.</param>
    /// <returns>The runtime resource builder.</returns>
    public static IResourceBuilder<CopilotRuntimeResource> WithCliPath(this IResourceBuilder<CopilotRuntimeResource> builder, string path)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentException.ThrowIfNullOrWhiteSpace(path);

        if (builder.Resource.InnerResource is not null)
        {
            // The executable is not used in container or existing mode.
            return builder;
        }

        var resolved = Path.IsPathRooted(path) || !path.Contains(Path.DirectorySeparatorChar) && !path.Contains(Path.AltDirectorySeparatorChar)
            ? path
            : Path.GetFullPath(path, builder.ApplicationBuilder.AppHostDirectory);
        return builder.WithCommand(resolved);
    }

    /// <summary>
    /// Sets <c>COPILOT_HOME</c> for executable mode. By default each runtime gets a directory scoped to the AppHost
    /// (under the local application data folder), so Aspire runs don't mix with interactive CLI sessions.
    /// </summary>
    /// <param name="builder">The runtime resource builder.</param>
    /// <param name="path">The directory; relative paths resolve against the AppHost directory.</param>
    /// <returns>The runtime resource builder.</returns>
    public static IResourceBuilder<CopilotRuntimeResource> WithHomeDirectory(this IResourceBuilder<CopilotRuntimeResource> builder, string path)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentException.ThrowIfNullOrWhiteSpace(path);

        builder.Resource.HomeDirectory = Path.GetFullPath(path, builder.ApplicationBuilder.AppHostDirectory);
        return builder;
    }

    /// <summary>
    /// Sets the runtime log level (<c>--log-level</c>).
    /// </summary>
    /// <param name="builder">The runtime resource builder.</param>
    /// <param name="level">The log level.</param>
    /// <returns>The runtime resource builder.</returns>
    public static IResourceBuilder<CopilotRuntimeResource> WithLogLevel(this IResourceBuilder<CopilotRuntimeResource> builder, CopilotLogLevel level)
    {
        ArgumentNullException.ThrowIfNull(builder);

        builder.Resource.LogLevel = level;
        return builder;
    }

    /// <summary>
    /// Sets the GitHub token the runtime signs in with (<c>COPILOT_GITHUB_TOKEN</c>). Without it, executable mode uses
    /// the developer's signed-in user.
    /// </summary>
    /// <param name="builder">The runtime resource builder.</param>
    /// <param name="token">A secret parameter with the token.</param>
    /// <returns>The runtime resource builder.</returns>
    public static IResourceBuilder<CopilotRuntimeResource> WithGitHubToken(this IResourceBuilder<CopilotRuntimeResource> builder, IResourceBuilder<ParameterResource> token)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentNullException.ThrowIfNull(token);

        RemoveDefaultGitHubToken(builder.Resource, builder.ApplicationBuilder);
        builder.Resource.GitHubTokenParameter = token.Resource;
        return builder;
    }

    /// <summary>
    /// Replaces the generated connection token (<c>COPILOT_CONNECTION_TOKEN</c>).
    /// </summary>
    /// <param name="builder">The runtime resource builder.</param>
    /// <param name="token">A secret parameter with the token.</param>
    /// <returns>The runtime resource builder.</returns>
    public static IResourceBuilder<CopilotRuntimeResource> WithConnectionToken(this IResourceBuilder<CopilotRuntimeResource> builder, IResourceBuilder<ParameterResource> token)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentNullException.ThrowIfNull(token);

        if (builder.Resource.IsExisting)
        {
            throw new InvalidOperationException($"'{builder.Resource.Name}' points at an existing runtime; pass its token to AsExisting.");
        }

        builder.Resource.ConnectionTokenParameter = token.Resource;
        return builder;
    }

    /// <summary>
    /// Persists <c>COPILOT_HOME</c> (sessions, settings) in a named volume. Applies in container mode; executable mode
    /// already uses a persistent AppHost-scoped directory.
    /// </summary>
    /// <param name="builder">The runtime resource builder.</param>
    /// <param name="name">The volume name; generated from the AppHost and resource names when <see langword="null"/>.</param>
    /// <returns>The runtime resource builder.</returns>
    public static IResourceBuilder<CopilotRuntimeResource> WithDataVolume(this IResourceBuilder<CopilotRuntimeResource> builder, string? name = null)
    {
        ArgumentNullException.ThrowIfNull(builder);

        return builder.WithAnnotation(new ContainerMountAnnotation(
            name ?? VolumeNameGenerator.Generate(builder, "data"),
            CopilotContainerResource.HomeDirectory,
            ContainerMountType.Volume,
            isReadOnly: false));
    }

    /// <summary>
    /// Persists <c>COPILOT_HOME</c> in a host directory: bind-mounted in container mode, used directly in executable mode.
    /// </summary>
    /// <param name="builder">The runtime resource builder.</param>
    /// <param name="source">The host directory; relative paths resolve against the AppHost directory.</param>
    /// <returns>The runtime resource builder.</returns>
    public static IResourceBuilder<CopilotRuntimeResource> WithDataBindMount(this IResourceBuilder<CopilotRuntimeResource> builder, string source)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentException.ThrowIfNullOrWhiteSpace(source);

        var fullPath = Path.GetFullPath(source, builder.ApplicationBuilder.AppHostDirectory);
        return builder.WithAnnotation(new ContainerMountAnnotation(fullPath, CopilotContainerResource.HomeDirectory, ContainerMountType.BindMount, isReadOnly: false));
    }

    /// <summary>
    /// Connects an MCP server resource to the runtime. Projects that reference the runtime or its models get
    /// <c>Copilot__McpServers__{server}__Type=http</c>, <c>__Url</c> (as the runtime reaches the server), and
    /// <c>__Tools__{i}</c>; the runtime waits for the server.
    /// </summary>
    /// <param name="builder">The runtime resource builder.</param>
    /// <param name="server">The MCP server (project, container, or executable) with an HTTP endpoint.</param>
    /// <param name="path">The MCP path on the server.</param>
    /// <param name="tools">
    /// The tools the agent may call. The core integration exposes only listed tools, so with none listed the app must
    /// configure <c>Copilot:McpServers:{server}:Tools</c> itself.
    /// </param>
    /// <param name="endpointName">The server endpoint; <c>http</c>, then <c>https</c>, then the first endpoint by default.</param>
    /// <returns>The runtime resource builder.</returns>
    public static IResourceBuilder<CopilotRuntimeResource> WithMcpServer(
        this IResourceBuilder<CopilotRuntimeResource> builder,
        IResourceBuilder<IResourceWithEndpoints> server,
        string path = "/mcp",
        string[]? tools = null,
        string? endpointName = null)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentNullException.ThrowIfNull(server);
        ArgumentNullException.ThrowIfNull(path);

        endpointName ??= server.Resource.Annotations.OfType<EndpointAnnotation>()
            .Select(e => e.Name)
            .OrderBy(n => n switch { "http" => 0, "https" => 1, _ => 2 })
            .FirstOrDefault()
            ?? throw new InvalidOperationException($"MCP server '{server.Resource.Name}' has no endpoints. Add one or pass endpointName.");

        if (path.Length > 0 && path[0] != '/')
        {
            path = "/" + path;
        }

        var annotation = new CopilotMcpServerAnnotation(server.Resource.Name, server.Resource, endpointName, path, tools ?? []);
        builder.WithAnnotation(annotation, ResourceAnnotationMutationBehavior.Append);

        if (!builder.Resource.IsExisting)
        {
            builder.WaitFor(server);
        }

        return builder;
    }

    /// <summary>
    /// Records prompts and responses in the runtime's telemetry
    /// (<c>OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT</c>). Off by default.
    /// </summary>
    /// <param name="builder">The runtime resource builder.</param>
    /// <param name="enabled">Whether to capture message content.</param>
    /// <returns>The runtime resource builder.</returns>
    public static IResourceBuilder<CopilotRuntimeResource> WithMessageContentCapture(this IResourceBuilder<CopilotRuntimeResource> builder, bool enabled = true)
    {
        ArgumentNullException.ThrowIfNull(builder);

        builder.Resource.CaptureMessageContent = enabled;
        return builder;
    }

    /// <summary>
    /// Sets the reasoning effort for the model. Projects that reference it get <c>Copilot__ReasoningEffort</c>.
    /// </summary>
    /// <param name="builder">The model resource builder.</param>
    /// <param name="effort">The effort: <c>low</c>, <c>medium</c>, <c>high</c>, or <c>xhigh</c>.</param>
    /// <returns>The model resource builder.</returns>
    public static IResourceBuilder<CopilotModelResource> WithReasoningEffort(this IResourceBuilder<CopilotModelResource> builder, string effort)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentException.ThrowIfNullOrWhiteSpace(effort);

        builder.Resource.ReasoningEffort = effort;
        return builder;
    }

    /// <summary>
    /// Serves the model through a bring-your-own-key provider. The provider's endpoint and key are added to the model
    /// connection string (<c>Provider=...;ProviderEndpoint=...;ProviderKey=...</c>).
    /// </summary>
    /// <param name="builder">The model resource builder.</param>
    /// <param name="provider">
    /// A resource with an OpenAI-compatible endpoint: its <c>Uri</c> or <c>Endpoint</c> connection property (or
    /// connection string key) is the base URL and <c>Key</c> the API key.
    /// </param>
    /// <param name="wireApi">The API format; the runtime default when <see langword="null"/>.</param>
    /// <param name="type">
    /// The provider type (<c>openai</c>, <c>azure</c>, or <c>anthropic</c>). Inferred when <see langword="null"/>:
    /// <c>azure</c> for Azure OpenAI and Foundry resources, otherwise <c>openai</c>.
    /// </param>
    /// <returns>The model resource builder.</returns>
    public static IResourceBuilder<CopilotModelResource> WithProvider(
        this IResourceBuilder<CopilotModelResource> builder,
        IResourceBuilder<IResourceWithConnectionString> provider,
        CopilotWireApi? wireApi = null,
        string? type = null)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentNullException.ThrowIfNull(provider);

        var resource = provider.Resource;
        var properties = resource.GetConnectionProperties().ToDictionary(p => p.Key, p => p.Value, StringComparer.OrdinalIgnoreCase);

        var endpoint = properties.TryGetValue("Uri", out var uri) ? uri
            : properties.TryGetValue("Endpoint", out var ep) ? ep
            : ReferenceExpression.Create($"{new ConnectionStringPart(resource, ["Endpoint", "Uri", "Url"])}");

        ReferenceExpression? key = properties.TryGetValue("Key", out var k) ? k
            : properties.Count == 0 ? ReferenceExpression.Create($"{new ConnectionStringPart(resource, ["Key", "ApiKey"])}")
            : null;

        builder.Resource.Provider = new CopilotProviderReference(resource, type ?? InferProviderType(resource), endpoint, key, wireApi);
        builder.WithReferenceRelationship(resource);
        return builder;
    }

    internal static string InferProviderType(IResource resource)
    {
        for (IResource? current = resource; current is not null; current = (current as IResourceWithParent)?.Parent)
        {
            var typeName = current.GetType().Name;
            if (typeName.Contains("AzureOpenAI", StringComparison.Ordinal) || typeName.Contains("Foundry", StringComparison.Ordinal)
                || typeName.Contains("AzureAI", StringComparison.Ordinal))
            {
                return "azure";
            }
        }

        return "openai";
    }

    private static void ConfigureContainer(IResourceBuilder<CopilotContainerResource> containerBuilder, Action<IResourceBuilder<CopilotContainerResource>>? configure)
    {
        if (configure is null)
        {
            return;
        }

        var container = containerBuilder.Resource;
        var imageBefore = container.Annotations.OfType<ContainerImageAnnotation>().LastOrDefault() is { } before ? (before.Registry, before.Image, before.Tag) : default;

        configure(containerBuilder);

        // WithImage keeps the Dockerfile build annotation; a prebuilt image replaces the bundled Dockerfile.
        var imageAfter = container.Annotations.OfType<ContainerImageAnnotation>().LastOrDefault() is { } after ? (after.Registry, after.Image, after.Tag) : default;
        if (imageAfter != imageBefore && container.Annotations.OfType<DockerfileBuildAnnotation>().LastOrDefault() is { } dockerfile)
        {
            container.Annotations.Remove(dockerfile);
        }
    }

    private static void RemoveDefaultGitHubToken(CopilotRuntimeResource runtime, IDistributedApplicationBuilder appBuilder)
    {
        if (runtime.DefaultGitHubTokenParameter is { } parameter)
        {
            appBuilder.Resources.Remove(parameter);
            if (runtime.GitHubTokenParameter == parameter)
            {
                runtime.GitHubTokenParameter = null;
            }

            runtime.DefaultGitHubTokenParameter = null;
        }
    }

    private static void AddArguments(CopilotRuntimeResource runtime, CommandLineArgsCallbackContext context)
    {
        context.Args.Add("--headless");
        context.Args.Add("--no-auto-update");

        if (runtime.LogLevel is { } level)
        {
            context.Args.Add("--log-level");
            context.Args.Add(level.Value);
        }

        if (runtime.Container is not null)
        {
            context.Args.Add("--host");
            context.Args.Add("0.0.0.0");
            context.Args.Add("--port");
            context.Args.Add(CopilotContainerResource.RuntimePort.ToString(System.Globalization.CultureInfo.InvariantCulture));
        }
        else
        {
            context.Args.Add("--host");
            context.Args.Add("127.0.0.1");
            context.Args.Add("--port");
            context.Args.Add(runtime.PrimaryEndpoint.Property(EndpointProperty.TargetPort));
        }
    }

    private static void AddEnvironment(CopilotRuntimeResource runtime, EnvironmentCallbackContext context, IDistributedApplicationBuilder builder)
    {
        context.EnvironmentVariables["COPILOT_CONNECTION_TOKEN"] = runtime.ConnectionTokenParameter;

        if (runtime.GitHubTokenParameter is { } gitHubToken)
        {
            context.EnvironmentVariables["COPILOT_GITHUB_TOKEN"] = gitHubToken;
        }

        context.EnvironmentVariables["COPILOT_HOME"] = runtime.Container is not null
            ? CopilotContainerResource.HomeDirectory
            : GetExecutableHome(runtime, builder);

        // Runs after the OTLP callback: export only when Aspire supplied an endpoint (not in publish mode or without a dashboard).
        if (context.EnvironmentVariables.ContainsKey("OTEL_EXPORTER_OTLP_ENDPOINT"))
        {
            context.EnvironmentVariables["COPILOT_OTEL_ENABLED"] = "true";
        }

        context.EnvironmentVariables[CaptureMessageContentVariable] = runtime.CaptureMessageContent ? "true" : "false";
    }

    internal static string GetExecutableHome(CopilotRuntimeResource runtime, IDistributedApplicationBuilder builder)
    {
        if (runtime.HomeDirectory is { } home)
        {
            return home;
        }

        if (runtime.Annotations.OfType<ContainerMountAnnotation>().LastOrDefault(m => m.Type == ContainerMountType.BindMount) is { Source: { } source })
        {
            return source;
        }

        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(builder.AppHostDirectory)))[..12].ToLowerInvariant();
        var root = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData, Environment.SpecialFolderOption.DoNotVerify);
        if (string.IsNullOrEmpty(root))
        {
            root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".local", "share");
        }

        return Path.Combine(root, "copilot-aspire", hash, runtime.Name);
    }

    private sealed class CopilotRuntimeRegistry : List<CopilotRuntimeResource>;

    private static CopilotRuntimeRegistry EnsureReferenceWiring(IDistributedApplicationBuilder builder)
    {
        if (builder.Services.FirstOrDefault(d => d.ServiceType == typeof(CopilotRuntimeRegistry))?.ImplementationInstance is CopilotRuntimeRegistry existing)
        {
            return existing;
        }

        var registry = new CopilotRuntimeRegistry();
        builder.Services.AddSingleton(registry);
        builder.Eventing.Subscribe<BeforeStartEvent>((e, _) =>
        {
            WireReferences(e.Model, registry);
            return Task.CompletedTask;
        });
        return registry;
    }

    /// <summary>
    /// Adds MCP server and reasoning effort settings to every resource that references a runtime or one of its models.
    /// </summary>
    internal static void WireReferences(DistributedApplicationModel model, IReadOnlyList<CopilotRuntimeResource> runtimes)
    {
        foreach (var resource in model.Resources.OfType<IResourceWithEnvironment>())
        {
            var targets = resource.Annotations.OfType<ResourceRelationshipAnnotation>()
                .Where(r => r.Type == "Reference")
                .Select(r => r.Resource)
                .ToList();

            foreach (var runtime in runtimes)
            {
                if (ReferenceEquals(resource, runtime) || ReferenceEquals(resource, runtime.InnerResource))
                {
                    continue;
                }

                var referencedModels = runtime.Models.Where(m => targets.Contains(m)).ToList();
                var referencesRuntime = targets.Any(t => ReferenceEquals(t, runtime) || ReferenceEquals(t, runtime.InnerResource));
                if (!referencesRuntime && referencedModels.Count == 0)
                {
                    continue;
                }

                var servers = runtime.Annotations.OfType<CopilotMcpServerAnnotation>().ToList();
                var reasoningEffort = referencedModels.Select(m => m.ReasoningEffort).LastOrDefault(e => e is not null);
                if (servers.Count == 0 && reasoningEffort is null)
                {
                    continue;
                }

                resource.Annotations.Add(new EnvironmentCallbackAnnotation(context => AddReferenceEnvironment(runtime, servers, reasoningEffort, context)));
            }
        }
    }

    private static void AddReferenceEnvironment(CopilotRuntimeResource runtime, List<CopilotMcpServerAnnotation> servers, string? reasoningEffort, EnvironmentCallbackContext context)
    {
        // The runtime, not the app, calls the MCP servers, so URLs resolve for the runtime's network.
        var network = runtime.Container is not null ? KnownNetworkIdentifiers.DefaultAspireContainerNetwork : KnownNetworkIdentifiers.LocalhostNetwork;

        foreach (var server in servers)
        {
            var prefix = $"Copilot__McpServers__{server.Name}__";
            var endpoint = new EndpointReference(server.Server, server.EndpointName, network);
            context.EnvironmentVariables[prefix + "Type"] = "http";
            context.EnvironmentVariables[prefix + "Url"] = ReferenceExpression.Create($"{endpoint.Property(EndpointProperty.Url)}{server.Path}");
            for (var i = 0; i < server.Tools.Count; i++)
            {
                context.EnvironmentVariables[$"{prefix}Tools__{i}"] = server.Tools[i];
            }
        }

        if (reasoningEffort is not null)
        {
            context.EnvironmentVariables["Copilot__ReasoningEffort"] = reasoningEffort;
        }
    }
}
