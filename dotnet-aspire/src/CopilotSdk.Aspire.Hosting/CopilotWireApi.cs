using Aspire.Hosting.ApplicationModel;

namespace CopilotSdk.Aspire.Hosting;

/// <summary>
/// The API format a bring-your-own-key provider speaks.
/// </summary>
public enum CopilotWireApi
{
    /// <summary>OpenAI Chat Completions (<c>completions</c>), the runtime's default.</summary>
    Completions,

    /// <summary>OpenAI Responses (<c>responses</c>).</summary>
    Responses,
}

/// <summary>
/// A bring-your-own-key provider attached to a <see cref="CopilotModelResource"/>.
/// </summary>
/// <param name="Resource">The resource that supplies the endpoint and key.</param>
/// <param name="Type">The provider type: <c>openai</c>, <c>azure</c>, or <c>anthropic</c>.</param>
/// <param name="Endpoint">The provider base URL.</param>
/// <param name="Key">The API key, if the resource exposes one.</param>
/// <param name="WireApi">The API format, if set.</param>
public sealed record CopilotProviderReference(
    IResourceWithConnectionString Resource,
    string Type,
    ReferenceExpression Endpoint,
    ReferenceExpression? Key,
    CopilotWireApi? WireApi);

internal static class CopilotWireApiExtensions
{
    public static string ToConnectionStringValue(this CopilotWireApi wireApi) => wireApi switch
    {
        CopilotWireApi.Completions => "completions",
        CopilotWireApi.Responses => "responses",
        _ => throw new ArgumentOutOfRangeException(nameof(wireApi), wireApi, null),
    };
}
