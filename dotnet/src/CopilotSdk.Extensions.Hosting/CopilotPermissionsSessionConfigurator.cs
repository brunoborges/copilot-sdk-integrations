using System.Text.Json;
using GitHub.Copilot;
using GitHub.Copilot.Rpc;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Installs the default permission handler: app tools run, <see cref="RequiresApprovalAttribute"/> tools go to
/// <see cref="CopilotOptions.OnApproval"/>, listed MCP tools are allowed, and everything else is denied.
/// </summary>
/// <remarks>
/// An app can replace the handler by setting <see cref="SessionConfigBase.OnPermissionRequest"/> in
/// <see cref="CopilotOptions.ConfigureSession"/>, which runs after this configurator.
/// </remarks>
internal sealed partial class CopilotPermissionsSessionConfigurator(
    CopilotToolCatalog catalog,
    IOptionsMonitor<CopilotOptions> options,
    IServiceProvider rootServices,
    CopilotTurnContextRegistry? turns = null,
    ILoggerFactory? loggerFactory = null) : ICopilotSessionConfigurator
{
    internal const string DeniedFeedback = "Denied by the application's permission policy.";
    internal const string NotApprovedFeedback = "Not approved.";

    private readonly ILogger _logger =
        (loggerFactory ?? Microsoft.Extensions.Logging.Abstractions.NullLoggerFactory.Instance).CreateLogger("CopilotSdk.Permissions");

    public void Configure(string agentName, SessionConfigBase config, IServiceProvider services)
    {
        var tools = catalog.GetTools(agentName);
        config.OnPermissionRequest = (request, invocation) => DecideAsync(agentName, tools, request, invocation);
    }

    internal Task<PermissionDecision> DecideAsync(
        string agentName,
        CopilotAgentTools tools,
        PermissionRequest request,
        PermissionInvocation invocation)
    {
        switch (request)
        {
            case PermissionRequestCustomTool customTool when tools.ApprovalRequired.Contains(customTool.ToolName):
                return ApproveAsync(agentName, customTool, invocation);

            case PermissionRequestCustomTool customTool when tools.ToolNames.Contains(customTool.ToolName):
                return Task.FromResult(PermissionDecision.ApproveOnce());

            case PermissionRequestMcp mcp when IsAllowedMcpTool(options.Get(agentName), mcp.ServerName, mcp.ToolName):
                return Task.FromResult(PermissionDecision.ApproveOnce());

            default:
                LogDenied(_logger, request.Kind, invocation.SessionId);
                return Task.FromResult(PermissionDecision.Reject(DeniedFeedback));
        }
    }

    private async Task<PermissionDecision> ApproveAsync(string agentName, PermissionRequestCustomTool request, PermissionInvocation invocation)
    {
        if (options.Get(agentName).OnApproval is not { } onApproval)
        {
            return PermissionDecision.Reject(NotApprovedFeedback);
        }

        try
        {
            var arguments = ToJsonElement(request.Args);
            var approved = await CopilotToolFactory.RunInTurnAsync(
                turns,
                rootServices,
                invocation.SessionId,
                async (services, ct) => await onApproval(
                    new CopilotApprovalRequest(request.ToolName, arguments, request, services, ct)).ConfigureAwait(false),
                CancellationToken.None).ConfigureAwait(false);

            return approved ? PermissionDecision.ApproveOnce() : PermissionDecision.Reject(NotApprovedFeedback);
        }
        catch (Exception ex)
        {
            LogApprovalFailed(_logger, ex, invocation.SessionId);
            return PermissionDecision.Reject(NotApprovedFeedback);
        }
    }

    private static JsonElement ToJsonElement(object? args) => args switch
    {
        JsonElement element => element,
        null => default,
        string json => JsonDocument.Parse(json).RootElement.Clone(),
        _ => JsonSerializer.SerializeToElement(args, args.GetType(), JsonSerializerOptions.Web),
    };

    /// <summary>
    /// An MCP tool is allowed when its server is configured on the agent and the server's
    /// <see cref="CopilotMcpServerOptions.Tools"/> lists it (or <c>*</c>). An empty list allows none.
    /// </summary>
    internal static bool IsAllowedMcpTool(CopilotOptions options, string serverName, string toolName)
    {
        if (string.IsNullOrEmpty(serverName) ||
            !options.McpServers.TryGetValue(serverName, out var server) ||
            server?.Tools is not { Count: > 0 } allowed)
        {
            return false;
        }

        foreach (var name in allowed)
        {
            if (name == "*" ||
                string.Equals(name, toolName, StringComparison.Ordinal) ||
                string.Equals($"{serverName}-{name}", toolName, StringComparison.Ordinal))
            {
                return true;
            }
        }

        return false;
    }

    [LoggerMessage(EventId = 1, Level = LogLevel.Information, Message = "Denied a '{Kind}' permission request (session {SessionId}).")]
    private static partial void LogDenied(ILogger logger, string kind, string sessionId);

    [LoggerMessage(EventId = 2, Level = LogLevel.Error, Message = "OnApproval failed; the request is denied (session {SessionId}).")]
    private static partial void LogApprovalFailed(ILogger logger, Exception exception, string sessionId);
}
