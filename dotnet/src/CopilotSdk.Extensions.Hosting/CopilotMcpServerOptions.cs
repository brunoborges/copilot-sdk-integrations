namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Configuration shape for an MCP server. Mirrors the SDK's <c>McpStdioServerConfig</c> and
/// <c>McpHttpServerConfig</c> (which are not directly bindable because their base type is abstract).
/// </summary>
public sealed class CopilotMcpServerOptions
{
    /// <summary>
    /// Server transport: <c>http</c> (also accepts <c>sse</c>) or <c>stdio</c> (also accepts <c>local</c>).
    /// When not set, <c>http</c> is used if <see cref="Url"/> is set and <c>stdio</c> if <see cref="Command"/> is set.
    /// </summary>
    public string? Type { get; set; }

    /// <summary>
    /// Tools the agent may call on this server. Only listed tools are exposed and allowed by the default permission
    /// handler; an empty list exposes none.
    /// </summary>
    public IList<string> Tools { get; set; } = [];

    /// <summary>Optional timeout in milliseconds for tool calls to this server.</summary>
    public int? Timeout { get; set; }

    /// <summary>URL of an <c>http</c> server.</summary>
    public string? Url { get; set; }

    /// <summary>HTTP headers sent to an <c>http</c> server.</summary>
    public IDictionary<string, string> Headers { get; set; } = new Dictionary<string, string>();

    /// <summary>Command that starts a <c>stdio</c> server.</summary>
    public string? Command { get; set; }

    /// <summary>Arguments for <see cref="Command"/>.</summary>
    public IList<string> Args { get; set; } = [];

    /// <summary>Environment variables for a <c>stdio</c> server.</summary>
    public IDictionary<string, string> Env { get; set; } = new Dictionary<string, string>();

    /// <summary>Working directory for a <c>stdio</c> server.</summary>
    public string? WorkingDirectory { get; set; }
}
