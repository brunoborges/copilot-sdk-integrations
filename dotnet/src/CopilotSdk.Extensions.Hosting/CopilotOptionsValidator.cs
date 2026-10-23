using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Validates <see cref="CopilotOptions"/> at startup. Messages name the configuration key to fix.
/// </summary>
/// <remarks>
/// Client-level settings (<see cref="CopilotOptions.Endpoint"/>, <see cref="CopilotOptions.ConnectionToken"/>,
/// <see cref="CopilotOptions.GitHubToken"/>, <see cref="CopilotOptions.MaxConcurrentTurns"/>,
/// <see cref="CopilotOptions.InProcess"/>, <see cref="CopilotOptions.SessionRetention"/>) are validated on the
/// default agent only, because the client is shared.
/// </remarks>
internal sealed class CopilotOptionsValidator : IValidateOptions<CopilotOptions>
{
    internal static readonly string[] HttpMcpTypes = ["http", "sse"];
    internal static readonly string[] StdioMcpTypes = ["stdio", "local"];

    public ValidateOptionsResult Validate(string? name, CopilotOptions options)
    {
        name ??= Options.DefaultName;
        var isDefault = name == Options.DefaultName;
        var prefix = isDefault
            ? CopilotOptions.SectionName + ":"
            : $"{CopilotOptions.SectionName}:{CopilotOptionsSetup.AgentsSectionName}:{name}:";
        const string clientPrefix = CopilotOptions.SectionName + ":";
        var failures = new List<string>();

        if (options.ResponseTimeout <= TimeSpan.Zero)
        {
            failures.Add($"{prefix}{nameof(CopilotOptions.ResponseTimeout)} must be positive.");
        }

        if (isDefault)
        {
            if (options.MaxConcurrentTurns is <= 0)
            {
                failures.Add($"{clientPrefix}{nameof(CopilotOptions.MaxConcurrentTurns)} must be positive when set.");
            }

            if (options.SessionRetention is { } retention && retention <= TimeSpan.Zero)
            {
                failures.Add($"{clientPrefix}{nameof(CopilotOptions.SessionRetention)} must be positive when set.");
            }

            if (options.InProcess && options.Endpoint is not null)
            {
                failures.Add($"{clientPrefix}{nameof(CopilotOptions.InProcess)} cannot be combined with {clientPrefix}{nameof(CopilotOptions.Endpoint)}: choose an in-process runtime or a shared one.");
            }

            ValidateEndpoint(options, clientPrefix, failures);
        }

        try
        {
            CopilotMessageSources.Parse(options.MessageSource);
        }
        catch (ArgumentException)
        {
            failures.Add($"{prefix}{nameof(CopilotOptions.MessageSource)} must be 'user', 'system', or 'agent-<id>' where <id> matches {CopilotMessageSources.AgentIdPattern}.");
        }

        if (options.Provider is { } provider && string.IsNullOrWhiteSpace(provider.BaseUrl))
        {
            failures.Add($"{prefix}{nameof(CopilotOptions.Provider)}:{nameof(provider.BaseUrl)} is required when {prefix}{nameof(CopilotOptions.Provider)} is set.");
        }

        foreach (var (serverName, server) in options.McpServers)
        {
            ValidateMcpServer($"{prefix}{nameof(CopilotOptions.McpServers)}:{serverName}", server, failures);
        }

        return failures.Count == 0 ? ValidateOptionsResult.Success : ValidateOptionsResult.Fail(failures);
    }

    private static void ValidateEndpoint(CopilotOptions options, string prefix, List<string> failures)
    {
        if (options.Endpoint is null)
        {
            return;
        }

        var endpointKey = prefix + nameof(CopilotOptions.Endpoint);
        if (!CopilotEndpoint.TryParse(options.Endpoint, out var endpoint))
        {
            failures.Add($"{endpointKey} '{options.Endpoint}' is not valid. Use tcp://host:port, http(s)://host:port, host:port, or a port number.");
            return;
        }

        if (!endpoint.IsLoopback && string.IsNullOrEmpty(options.ConnectionToken))
        {
            failures.Add($"{prefix}{nameof(CopilotOptions.ConnectionToken)} is required because {endpointKey} '{endpoint.Host}' is not a loopback address.");
        }

        if (!string.IsNullOrEmpty(options.GitHubToken))
        {
            failures.Add($"{prefix}{nameof(CopilotOptions.GitHubToken)} cannot be combined with {endpointKey}: a shared runtime manages its own sign-in. Configure the token on the runtime, or use {nameof(CopilotOptions.GitHubTokenProvider)} for per-user tokens.");
        }
    }

    private static void ValidateMcpServer(string key, CopilotMcpServerOptions? server, List<string> failures)
    {
        if (server is null)
        {
            failures.Add($"{key} is empty.");
            return;
        }

        if (server.Timeout is <= 0)
        {
            failures.Add($"{key}:{nameof(CopilotMcpServerOptions.Timeout)} must be positive when set.");
        }

        switch (CopilotMcpServerMapper.ResolveTransport(server))
        {
            case CopilotMcpTransport.Http:
                if (!Uri.TryCreate(server.Url, UriKind.Absolute, out var url)
                    || (url.Scheme != Uri.UriSchemeHttp && url.Scheme != Uri.UriSchemeHttps))
                {
                    failures.Add($"{key}:{nameof(CopilotMcpServerOptions.Url)} must be an absolute http(s) URL for an http server.");
                }

                break;

            case CopilotMcpTransport.Stdio:
                if (string.IsNullOrWhiteSpace(server.Command))
                {
                    failures.Add($"{key}:{nameof(CopilotMcpServerOptions.Command)} is required for a stdio server.");
                }

                break;

            case CopilotMcpTransport.Unknown when server.Type is null:
                failures.Add($"{key} needs a {nameof(CopilotMcpServerOptions.Url)} (http) or a {nameof(CopilotMcpServerOptions.Command)} (stdio).");
                break;

            default:
                failures.Add($"{key}:{nameof(CopilotMcpServerOptions.Type)} '{server.Type}' is not supported. Use 'http' or 'stdio'.");
                break;
        }
    }
}
