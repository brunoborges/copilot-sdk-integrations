using System.Data.Common;
using System.Diagnostics.CodeAnalysis;
using GitHub.Copilot;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Values read from a Copilot connection string (<c>ConnectionStrings:copilot</c>).
/// </summary>
internal sealed class CopilotConnectionInfo
{
    public string? Endpoint { get; init; }

    public string? ConnectionToken { get; init; }

    public string? Model { get; init; }

    /// <summary>BYOK provider type, for example <c>openai</c> or <c>azure</c>.</summary>
    public string? Provider { get; init; }

    public string? ProviderEndpoint { get; init; }

    public string? ProviderKey { get; init; }

    public string? WireApi { get; init; }

    /// <summary>Informational list of MCP server names; their details flow as <c>Copilot__McpServers__*</c> settings.</summary>
    public IReadOnlyList<string> McpServers { get; init; } = [];

    /// <summary>Keys that were present but not recognized (ignored so producer and consumer versions can drift).</summary>
    public IReadOnlyList<string> UnknownKeys { get; init; } = [];

    /// <summary>
    /// Fills values on <paramref name="options"/> that are not already set. <see cref="CopilotOptions.Endpoint"/> and
    /// <see cref="CopilotOptions.ConnectionToken"/> are applied together, and only when no endpoint is set, so an
    /// explicit endpoint is never paired with another runtime's token.
    /// </summary>
    public void ApplyTo(CopilotOptions options)
    {
        if (options.Endpoint is null && Endpoint is not null)
        {
            options.Endpoint = Endpoint;
            options.ConnectionToken ??= ConnectionToken;
        }

        options.Model ??= Model;

        if (options.Provider is null && Provider is not null)
        {
            options.Provider = new ProviderConfig
            {
                Type = Provider,
                BaseUrl = ProviderEndpoint ?? string.Empty,
                ApiKey = ProviderKey,
                WireApi = WireApi,
            };
        }
    }
}

/// <summary>
/// Parses Copilot connection strings:
/// <c>Endpoint=tcp://host:port;ConnectionToken=...;Model=...[;Provider=...;ProviderEndpoint=...;ProviderKey=...;WireApi=...][;McpServers=a,b]</c>.
/// Keys are case-insensitive; unknown keys are reported in <see cref="CopilotConnectionInfo.UnknownKeys"/>.
/// A bare absolute URI (for example <c>tcp://localhost:5000</c>) is accepted as the endpoint.
/// </summary>
internal static class CopilotConnectionString
{
    public const string EndpointKey = "Endpoint";
    public const string ConnectionTokenKey = "ConnectionToken";
    public const string ModelKey = "Model";
    public const string ProviderKey = "Provider";
    public const string ProviderEndpointKey = "ProviderEndpoint";
    public const string ProviderKeyKey = "ProviderKey";
    public const string WireApiKey = "WireApi";
    public const string McpServersKey = "McpServers";

    public static bool TryParse(string? connectionString, [NotNullWhen(true)] out CopilotConnectionInfo? info)
    {
        info = null;
        if (string.IsNullOrWhiteSpace(connectionString))
        {
            return false;
        }

        var trimmed = connectionString.Trim();
        if (!trimmed.Contains('=', StringComparison.Ordinal))
        {
            if (!Uri.TryCreate(trimmed, UriKind.Absolute, out _))
            {
                return false;
            }

            info = new CopilotConnectionInfo { Endpoint = trimmed };
            return true;
        }

        var builder = new DbConnectionStringBuilder();
        try
        {
            builder.ConnectionString = trimmed;
        }
        catch (ArgumentException)
        {
            return false;
        }

        string? Get(string key) =>
            builder.TryGetValue(key, out var value) && value is string s && !string.IsNullOrWhiteSpace(s) ? s.Trim() : null;

        var known = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            EndpointKey, ConnectionTokenKey, ModelKey, ProviderKey, ProviderEndpointKey, ProviderKeyKey, WireApiKey, McpServersKey,
        };

        info = new CopilotConnectionInfo
        {
            Endpoint = Get(EndpointKey),
            ConnectionToken = Get(ConnectionTokenKey),
            Model = Get(ModelKey),
            Provider = Get(ProviderKey),
            ProviderEndpoint = Get(ProviderEndpointKey),
            ProviderKey = Get(ProviderKeyKey),
            WireApi = Get(WireApiKey),
            McpServers = Get(McpServersKey)?.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries) ?? [],
            UnknownKeys = builder.Keys.Cast<string>().Where(k => !known.Contains(k)).ToArray(),
        };
        return true;
    }
}
