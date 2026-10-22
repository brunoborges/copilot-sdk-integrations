using Aspire.Hosting.ApplicationModel;

namespace CopilotSdk.Aspire.Hosting;

/// <summary>
/// An HTTP MCP server connected to a <see cref="CopilotRuntimeResource"/> with <c>WithMcpServer</c>.
/// </summary>
/// <param name="Name">The MCP server name (the server resource name).</param>
/// <param name="Server">The resource that hosts the server.</param>
/// <param name="EndpointName">The endpoint the runtime calls.</param>
/// <param name="Path">The MCP path on the endpoint, for example <c>/mcp</c>.</param>
/// <param name="Tools">The allowed tools. Core exposes only listed tools, so an empty list exposes none until the app configures <c>Copilot:McpServers:{name}:Tools</c>.</param>
public sealed record CopilotMcpServerAnnotation(
    string Name,
    IResourceWithEndpoints Server,
    string EndpointName,
    string Path,
    IReadOnlyList<string> Tools) : IResourceAnnotation;
