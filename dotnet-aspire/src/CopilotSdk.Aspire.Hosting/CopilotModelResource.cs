using Aspire.Hosting.ApplicationModel;

namespace CopilotSdk.Aspire.Hosting;

/// <summary>
/// A model served by a <see cref="CopilotRuntimeResource"/>. Its connection string is the runtime's plus
/// <c>;Model={model}</c>, and the BYOK provider when <c>WithProvider</c> is used.
/// </summary>
public sealed class CopilotModelResource : Resource, IResourceWithParent<CopilotRuntimeResource>, IResourceWithConnectionString
{
    /// <summary>
    /// Creates a model resource.
    /// </summary>
    /// <param name="name">The resource name, also the connection name services use.</param>
    /// <param name="model">The model identifier, for example <c>gpt-5.4</c>.</param>
    /// <param name="parent">The runtime that serves the model.</param>
    public CopilotModelResource(string name, string model, CopilotRuntimeResource parent)
        : base(name)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(model);
        ArgumentNullException.ThrowIfNull(parent);
        Model = model;
        Parent = parent;
    }

    /// <summary>The model identifier.</summary>
    public string Model { get; }

    /// <summary>The runtime that serves the model.</summary>
    public CopilotRuntimeResource Parent { get; }

    // State, health, and connection string events flow from the resource that actually runs.
    IResource IResourceWithParent.Parent => Parent.InnerResource ?? Parent;

    /// <summary>The reasoning effort for this model (<c>low</c>, <c>medium</c>, <c>high</c>, <c>xhigh</c>), if set.</summary>
    public string? ReasoningEffort { get; internal set; }

    /// <summary>The bring-your-own-key provider, if set with <c>WithProvider</c>.</summary>
    public CopilotProviderReference? Provider { get; internal set; }

    /// <summary>
    /// The connection string: <c>{runtime connection string};Model={model}</c>, plus
    /// <c>;Provider=...;ProviderEndpoint=...;ProviderKey=...;WireApi=...</c> when a provider is set.
    /// </summary>
    public ReferenceExpression ConnectionStringExpression
    {
        get
        {
            var builder = new ReferenceExpressionBuilder();
            builder.Append($"{Parent.ConnectionStringExpression};Model={Model}");

            if (Provider is { } provider)
            {
                builder.Append($";Provider={provider.Type};ProviderEndpoint={provider.Endpoint}");
                if (provider.Key is { } key)
                {
                    builder.Append($";ProviderKey={key}");
                }

                if (provider.WireApi is { } wireApi)
                {
                    builder.Append($";WireApi={wireApi.ToConnectionStringValue()}");
                }
            }

            return builder.Build();
        }
    }

    IEnumerable<KeyValuePair<string, ReferenceExpression>> IResourceWithConnectionString.GetConnectionProperties()
    {
        foreach (var property in ((IResourceWithConnectionString)Parent).GetConnectionProperties())
        {
            yield return property;
        }

        yield return new("Model", ReferenceExpression.Create($"{Model}"));
    }
}
