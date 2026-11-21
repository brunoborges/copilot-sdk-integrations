using System.Diagnostics.CodeAnalysis;
using System.Net;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Parses <see cref="CopilotOptions.Endpoint"/> values. Accepts <c>tcp://host:port</c>, <c>http(s)://host[:port]</c>,
/// <c>host:port</c>, and <c>port</c> (loopback).
/// </summary>
internal sealed class CopilotEndpoint
{
    private CopilotEndpoint(string host, int port)
    {
        Host = host;
        Port = port;
    }

    public string Host { get; }

    public int Port { get; }

    /// <summary>The host to pass to <c>RuntimeConnection.ForTcp</c>, which does not accept IPv6 brackets.</summary>
    public string ConnectionHost => Host.Trim('[', ']');

    public bool IsLoopback =>
        string.Equals(Host, "localhost", StringComparison.OrdinalIgnoreCase)
        || Host.EndsWith(".localhost", StringComparison.OrdinalIgnoreCase)
        || (IPAddress.TryParse(ConnectionHost, out var address) && IPAddress.IsLoopback(address));

    public static bool TryParse(string? value, [NotNullWhen(true)] out CopilotEndpoint? endpoint)
    {
        endpoint = null;
        if (string.IsNullOrWhiteSpace(value))
        {
            return false;
        }

        value = value.Trim();

        if (int.TryParse(value, out var portOnly))
        {
            if (!IsValidPort(portOnly))
            {
                return false;
            }

            endpoint = new CopilotEndpoint("localhost", portOnly);
            return true;
        }

        var hasScheme = value.Contains("://", StringComparison.Ordinal);
        if (!Uri.TryCreate(hasScheme ? value : "tcp://" + value, UriKind.Absolute, out var uri)
            || string.IsNullOrEmpty(uri.Host)
            || (uri.AbsolutePath.Length > 1 && !IsHttp(uri)))
        {
            return false;
        }

        if (IsHttp(uri))
        {
            endpoint = new CopilotEndpoint(uri.Host, uri.Port);
            return true;
        }

        if (!string.Equals(uri.Scheme, "tcp", StringComparison.OrdinalIgnoreCase) || uri.IsDefaultPort || !IsValidPort(uri.Port))
        {
            return false;
        }

        endpoint = new CopilotEndpoint(uri.Host, uri.Port);
        return true;
    }

    private static bool IsHttp(Uri uri) =>
        uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps;

    private static bool IsValidPort(int port) => port is > 0 and <= 65535;
}
