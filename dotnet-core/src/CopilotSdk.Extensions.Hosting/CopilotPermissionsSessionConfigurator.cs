using GitHub.Copilot;
using GitHub.Copilot.Rpc;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Installs the default permission handler: app tools run, <see cref="RequiresApprovalAttribute"/> tools go to
/// <see cref="CopilotOptions.OnApproval"/>, listed MCP tools are allowed, and everything else is denied.
/// </summary>
/// <remarks>
/// An app can replace the handler by setting <see cref="SessionConfigBase.OnPermissionRequest"/> in
/// <see cref="CopilotOptions.ConfigureSession"/>, which runs after this configurator.
/// </remarks>
internal sealed class CopilotPermissionsSessionConfigurator : ICopilotSessionConfigurator
{
    public void Configure(string agentName, SessionConfigBase config, IServiceProvider services)
    {
        // TODO(permissions agent): route custom-tool requests for [RequiresApproval] tools to OnApproval, allow MCP
        // tools listed in CopilotMcpServerOptions.Tools, deny the rest. Until then, deny everything (safe default).
        config.OnPermissionRequest ??= static (_, _) => Task.FromResult(PermissionDecision.Reject());
    }
}
