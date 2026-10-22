using System.Data.Common;
using System.Diagnostics.CodeAnalysis;
using Aspire.Hosting.ApplicationModel;

namespace CopilotSdk.Aspire.Hosting;

internal static class CopilotRuntimeConnectionString
{
    /// <summary>
    /// Reads the runtime URL (in the form <c>RuntimeConnection.ForUri</c> accepts) and token from a connection string.
    /// </summary>
    public static bool TryParse(string? connectionString, [NotNullWhen(true)] out string? url, out string? token)
    {
        url = null;
        token = null;
        if (!TryGetValues(connectionString, out var values) || !values.TryGetValue("Endpoint", out var endpoint))
        {
            return false;
        }

        values.TryGetValue("ConnectionToken", out token);
        url = Uri.TryCreate(endpoint, UriKind.Absolute, out var uri) && uri.Scheme == "tcp" ? $"{uri.Host}:{uri.Port}" : endpoint;
        return true;
    }

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
