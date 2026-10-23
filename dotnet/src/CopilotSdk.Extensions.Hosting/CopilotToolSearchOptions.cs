namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Configuration shape for the runtime's built-in tool search, which defers MCP and external tool definitions behind
/// a <c>tool_search_tool</c> once the agent has many tools. Mirrors the SDK's <c>ToolSearchConfig</c>.
/// When no value is set, the runtime default applies (enabled, threshold 30).
/// </summary>
public sealed class CopilotToolSearchOptions
{
    /// <summary>Enables or disables tool search. When <see langword="null"/>, the runtime default applies.</summary>
    public bool? Enabled { get; set; }

    /// <summary>
    /// The tool count above which MCP and external tools are deferred behind tool search. Must be at least 1.
    /// When <see langword="null"/>, the runtime default (30) applies. Has no effect when <see cref="Enabled"/> is
    /// <see langword="false"/>.
    /// </summary>
    public int? DeferThreshold { get; set; }
}
