using GitHub.Copilot;

namespace CopilotSdk.Extensions.Hosting;

internal enum CopilotMcpTransport
{
    Unknown,
    Http,
    Stdio,
}

/// <summary>
/// Maps <see cref="CopilotMcpServerOptions"/> to the SDK's <see cref="McpHttpServerConfig"/> or
/// <see cref="McpStdioServerConfig"/>.
/// </summary>
internal static class CopilotMcpServerMapper
{
    /// <summary>
    /// Resolves the transport from <see cref="CopilotMcpServerOptions.Type"/> (<c>http</c>/<c>sse</c> or
    /// <c>stdio</c>/<c>local</c>, case-insensitive). When the type is not set, a <c>Url</c> means http and a
    /// <c>Command</c> means stdio.
    /// </summary>
    public static CopilotMcpTransport ResolveTransport(CopilotMcpServerOptions server)
    {
        if (string.IsNullOrWhiteSpace(server.Type))
        {
            return !string.IsNullOrWhiteSpace(server.Url) ? CopilotMcpTransport.Http
                : !string.IsNullOrWhiteSpace(server.Command) ? CopilotMcpTransport.Stdio
                : CopilotMcpTransport.Unknown;
        }

        var type = server.Type.Trim();
        if (CopilotOptionsValidator.HttpMcpTypes.Contains(type, StringComparer.OrdinalIgnoreCase))
        {
            return CopilotMcpTransport.Http;
        }

        return CopilotOptionsValidator.StdioMcpTypes.Contains(type, StringComparer.OrdinalIgnoreCase)
            ? CopilotMcpTransport.Stdio
            : CopilotMcpTransport.Unknown;
    }

    /// <summary>
    /// Maps a server. <see cref="McpServerConfig.Tools"/> is the (deduplicated) <see cref="CopilotMcpServerOptions.Tools"/>
    /// list: only listed tools are exposed, and an empty list exposes none.
    /// </summary>
    public static McpServerConfig? Map(CopilotMcpServerOptions server)
    {
        McpServerConfig? config = ResolveTransport(server) switch
        {
            CopilotMcpTransport.Http => new McpHttpServerConfig
            {
                Url = server.Url ?? string.Empty,
                Headers = server.Headers.Count > 0 ? new Dictionary<string, string>(server.Headers) : null,
            },
            CopilotMcpTransport.Stdio => new McpStdioServerConfig
            {
                Command = server.Command ?? string.Empty,
                Args = server.Args.Count > 0 ? [.. server.Args] : null,
                Env = server.Env.Count > 0 ? new Dictionary<string, string>(server.Env) : null,
                WorkingDirectory = server.WorkingDirectory,
            },
            _ => null,
        };

        if (config is not null)
        {
            config.Tools = server.Tools.Distinct(StringComparer.Ordinal).ToList();
            config.Timeout = server.Timeout;
        }

        return config;
    }
}
