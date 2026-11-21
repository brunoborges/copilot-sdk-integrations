using System.Data.Common;
using System.Diagnostics.CodeAnalysis;
using System.Globalization;
using Aspire.Hosting.ApplicationModel;

namespace CopilotSdk.Aspire.Hosting;

internal static class CopilotRuntimeConnectionString
{
    /// <summary>
    /// Reads the runtime host and port (in the form <c>RuntimeConnection.ForTcp</c> accepts) and token from a
    /// connection string. The endpoint may be <c>tcp://host:port</c>, <c>http(s)://host[:port]</c>, <c>host:port</c>,
    /// or a loopback <c>port</c>; only the host and port are used.
    /// </summary>
    public static bool TryParse(string? connectionString, [NotNullWhen(true)] out string? host, out int port, out string? token)
    {
        host = null;
        port = 0;
        token = null;
        if (!TryGetValues(connectionString, out var values)
            || !values.TryGetValue("Endpoint", out var endpoint)
            || !TryParseEndpoint(endpoint, out host, out port))
        {
            return false;
        }

        values.TryGetValue("ConnectionToken", out token);
        return true;
    }

    private static bool TryParseEndpoint(string endpoint, [NotNullWhen(true)] out string? host, out int port)
    {
        host = null;
        if (int.TryParse(endpoint, NumberStyles.None, CultureInfo.InvariantCulture, out port))
        {
            host = "localhost";
            return IsValidPort(port);
        }

        var value = endpoint.Contains("://", StringComparison.Ordinal) ? endpoint : "tcp://" + endpoint;
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) || string.IsNullOrEmpty(uri.Host) || !IsValidPort(uri.Port))
        {
            return false;
        }

        // ForTcp takes IPv6 addresses without brackets.
        host = uri.Host.Trim('[', ']');
        port = uri.Port;
        return true;
    }

    private static bool IsValidPort(int port) => port is > 0 and <= 65535;

    /// <summary>
    /// Reads <c>key=value</c> pairs; a bare absolute URI becomes <c>Endpoint</c>.
    /// </summary>
    public static bool TryGetValues(string? connectionString, [NotNullWhen(true)] out Dictionary<string, string>? values)
    {
        values = null;
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

            values = new(StringComparer.OrdinalIgnoreCase) { ["Endpoint"] = trimmed };
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

        values = new(StringComparer.OrdinalIgnoreCase);
        foreach (string key in builder.Keys)
        {
            if (builder[key] is string value && !string.IsNullOrWhiteSpace(value))
            {
                values[key] = value.Trim();
            }
        }

        return true;
    }
}

/// <summary>
/// One value from another resource's connection string, read when the value is resolved. Used for BYOK providers
/// that do not expose connection properties.
/// </summary>
internal sealed class ConnectionStringPart(IResourceWithConnectionString resource, string[] keys)
    : IValueProvider, IManifestExpressionProvider, IValueWithReferences
{
    // A manifest cannot address part of a connection string; publish targets resolve the whole expression.
    public string ValueExpression => resource.ValueExpression;

    public IEnumerable<object> References => [resource];

    public async ValueTask<string?> GetValueAsync(CancellationToken cancellationToken = default) =>
        Select(await resource.GetConnectionStringAsync(cancellationToken).ConfigureAwait(false));

    public async ValueTask<string?> GetValueAsync(ValueProviderContext context, CancellationToken cancellationToken = default) =>
        Select(await resource.ConnectionStringExpression.GetValueAsync(context, cancellationToken).ConfigureAwait(false));

    private string? Select(string? connectionString)
    {
        if (!CopilotRuntimeConnectionString.TryGetValues(connectionString, out var values))
        {
            return null;
        }

        foreach (var key in keys)
        {
            if (values.TryGetValue(key, out var value))
            {
                return value;
            }
        }

        return null;
    }
}
