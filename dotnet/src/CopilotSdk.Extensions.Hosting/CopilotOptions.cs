using GitHub.Copilot;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Settings for a Copilot agent registered with
/// <see cref="Microsoft.Extensions.DependencyInjection.CopilotServiceCollectionExtensions.AddCopilot(Microsoft.Extensions.DependencyInjection.IServiceCollection, System.Action{CopilotOptions}?)"/>.
/// </summary>
/// <remarks>
/// <para>
/// Options are named: the default agent uses <see cref="Microsoft.Extensions.Options.Options.DefaultName"/>,
/// and a named agent uses its name. Values bind from the <c>Copilot</c> configuration section
/// (and <c>Copilot:Agents:{name}</c> for named agents); configuration overrides values set in code.
/// </para>
/// <para>
/// Client-level settings (<see cref="Endpoint"/>, <see cref="ConnectionToken"/>, <see cref="ConnectionName"/>,
/// <see cref="GitHubToken"/>, <see cref="EagerStart"/>, <see cref="MaxConcurrentTurns"/>, <see cref="InProcess"/>,
/// <see cref="SessionRetention"/>, and <see cref="ConfigureClient"/>) are read from the default agent only, because every agent shares one
/// <see cref="CopilotClient"/>.
/// </para>
/// </remarks>
public sealed class CopilotOptions
{
    /// <summary>The default value of <see cref="ConnectionName"/>.</summary>
    public const string DefaultConnectionName = "copilot";

    /// <summary>The configuration section the options bind from.</summary>
    public const string SectionName = "Copilot";

    /// <summary>The model to use, for example <c>gpt-5.4</c>. When <see langword="null"/>, the runtime default is used.</summary>
    public string? Model { get; set; }

    /// <summary>Instructions appended to the agent's system message.</summary>
    public string? Instructions { get; set; }

    /// <summary>Reasoning effort for models that support it (for example <c>low</c>, <c>medium</c>, <c>high</c>).</summary>
    public string? ReasoningEffort { get; set; }

    /// <summary>Maximum time to wait for a turn to complete. The turn is aborted when it elapses. Defaults to two minutes.</summary>
    public TimeSpan ResponseTimeout { get; set; } = TimeSpan.FromMinutes(2);

    /// <summary>Optional cap on concurrent turns across the application. Extra turns wait in a bounded queue.</summary>
    public int? MaxConcurrentTurns { get; set; }

    /// <summary>When <see langword="true"/>, the client starts with the host instead of on first use.</summary>
    public bool EagerStart { get; set; }

    /// <summary>
    /// GitHub token used as the service identity when the SDK starts its bundled runtime. Store it in a secret store.
    /// When unset, the first of the <c>COPILOT_GITHUB_TOKEN</c>, <c>GH_TOKEN</c>, and <c>GITHUB_TOKEN</c> environment
    /// variables is used; when none is set, the signed-in Copilot CLI user is used.
    /// Cannot be combined with <see cref="Endpoint"/>: a shared runtime manages its own sign-in.
    /// </summary>
    public string? GitHubToken { get; set; }

    /// <summary>
    /// Address of a shared, already-running runtime: <c>tcp://host:port</c>, <c>http(s)://host:port</c>,
    /// <c>host:port</c>, or a loopback port number. When <see langword="null"/>, the SDK starts its bundled runtime.
    /// </summary>
    public string? Endpoint { get; set; }

    /// <summary>
    /// When <see langword="true"/>, the SDK hosts the runtime in this process instead of starting a child process.
    /// Experimental in the SDK. Cannot be combined with <see cref="Endpoint"/>, and runtime OpenTelemetry export is
    /// not available in-process.
    /// </summary>
    [System.Diagnostics.CodeAnalysis.Experimental("GHCP001")]
    public bool InProcess { get; set; }

    /// <summary>
    /// When set, sessions not modified for this long are deleted in the background. Defaults to
    /// <see langword="null"/> (keep sessions forever). Sweeps run only once the client has started, and skip
    /// conversations with a turn in flight. With <see cref="Endpoint"/>, every session on the shared runtime is
    /// eligible, including those of other applications.
    /// </summary>
    public TimeSpan? SessionRetention { get; set; }

    /// <summary>Shared secret for <see cref="Endpoint"/>. Required when the endpoint is not loopback.</summary>
    public string? ConnectionToken { get; set; }

    /// <summary>
    /// Name of the connection string (<c>ConnectionStrings:{name}</c>) that can supply <see cref="Endpoint"/>,
    /// <see cref="ConnectionToken"/>, and <see cref="Model"/>. Defaults to <c>copilot</c>.
    /// Explicit <c>Copilot:*</c> values win.
    /// </summary>
    public string ConnectionName { get; set; } = DefaultConnectionName;

    /// <summary>
    /// MCP servers available to the agent, keyed by server name. Named agents inherit the default agent's servers
    /// and can add or override entries.
    /// </summary>
    public IDictionary<string, CopilotMcpServerOptions> McpServers { get; set; } =
        new Dictionary<string, CopilotMcpServerOptions>(StringComparer.OrdinalIgnoreCase);

    /// <summary>Bring-your-own-key model provider, using the SDK's <see cref="ProviderConfig"/> shape.</summary>
    public ProviderConfig? Provider { get; set; }

    /// <summary>
    /// Supplies a per-user GitHub token at the start of each turn. When set and it returns
    /// <see langword="null"/>, the call fails instead of falling back to the service identity.
    /// </summary>
    public Func<IServiceProvider, CancellationToken, Task<string?>>? GitHubTokenProvider { get; set; }

    /// <summary>
    /// Decides calls to tools marked with <see cref="RequiresApprovalAttribute"/>.
    /// Return <see langword="true"/> to approve. When <see langword="null"/>, such calls are denied.
    /// </summary>
    /// <example><c>o.OnApproval = call => ValueTask.FromResult(call.GetArgument&lt;decimal&gt;("amount") &lt; 100);</c></example>
    public Func<CopilotApprovalRequest, ValueTask<bool>>? OnApproval { get; set; }

    internal List<Action<CopilotClientOptions>> ClientConfigurations { get; } = [];

    internal List<Action<SessionConfigBase>> SessionConfigurations { get; } = [];

    /// <summary>
    /// Configures the SDK's <see cref="CopilotClientOptions"/> directly. Applied after the integration's
    /// own settings. Only honored on the default agent, because the client is shared.
    /// </summary>
    /// <param name="configure">The action that configures the client options.</param>
    /// <returns>This instance.</returns>
    public CopilotOptions ConfigureClient(Action<CopilotClientOptions> configure)
    {
        ArgumentNullException.ThrowIfNull(configure);
        ClientConfigurations.Add(configure);
        return this;
    }

    /// <summary>
    /// Configures the SDK's <see cref="SessionConfigBase"/> directly, for both new and resumed sessions.
    /// Applied after the integration's own settings, so it can replace them (for example
    /// <see cref="SessionConfigBase.OnPermissionRequest"/>).
    /// </summary>
    /// <param name="configure">The action that configures the session.</param>
    /// <returns>This instance.</returns>
    public CopilotOptions ConfigureSession(Action<SessionConfigBase> configure)
    {
        ArgumentNullException.ThrowIfNull(configure);
        SessionConfigurations.Add(configure);
        return this;
    }
}
