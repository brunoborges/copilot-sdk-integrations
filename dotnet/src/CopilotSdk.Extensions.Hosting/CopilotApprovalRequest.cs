using System.Text.Json;
using GitHub.Copilot;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// A pending call to a tool marked with <see cref="RequiresApprovalAttribute"/>, passed to
/// <see cref="CopilotOptions.OnApproval"/>.
/// </summary>
public sealed class CopilotApprovalRequest
{
    internal CopilotApprovalRequest(
        string toolName,
        JsonElement arguments,
        PermissionRequest request,
        IServiceProvider services,
        CancellationToken cancellationToken)
    {
        ToolName = toolName;
        Arguments = arguments;
        Request = request;
        Services = services;
        CancellationToken = cancellationToken;
    }

    /// <summary>The tool name as the model sees it (for example <c>refund_order</c>).</summary>
    public string ToolName { get; }

    /// <summary>The arguments the model supplied, as a JSON object.</summary>
    public JsonElement Arguments { get; }

    /// <summary>The services of the calling turn (the request scope in ASP.NET Core).</summary>
    public IServiceProvider Services { get; }

    /// <summary>Cancelled when the turn is cancelled.</summary>
    public CancellationToken CancellationToken { get; }

    /// <summary>The underlying SDK permission request.</summary>
    public PermissionRequest Request { get; }

    /// <summary>
    /// Reads an argument by name (case-insensitive) and deserializes it to <typeparamref name="T"/>.
    /// Returns <see langword="default"/> when the argument is absent.
    /// </summary>
    public T? GetArgument<T>(string name)
    {
        ArgumentException.ThrowIfNullOrEmpty(name);

        if (Arguments.ValueKind != JsonValueKind.Object)
        {
            return default;
        }

        foreach (var property in Arguments.EnumerateObject())
        {
            if (string.Equals(property.Name, name, StringComparison.OrdinalIgnoreCase))
            {
                return property.Value.Deserialize<T>(JsonSerializerOptions.Web);
            }
        }

        return default;
    }
}
