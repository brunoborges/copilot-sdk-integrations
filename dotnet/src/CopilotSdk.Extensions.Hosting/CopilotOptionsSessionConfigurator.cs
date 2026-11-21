using Microsoft.Copilot;
using Microsoft.Extensions.Options;

namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Applies <see cref="CopilotOptions"/> values (model, instructions, reasoning effort, tool search, BYOK provider, MCP servers)
/// to the session options.
/// </summary>
internal sealed class CopilotOptionsSessionConfigurator(IOptionsMonitor<CopilotOptions> options) : ICopilotSessionConfigurator
{
    /// <summary>The connection name the BYOK <see cref="CopilotOptions.Provider"/> is registered under.</summary>
    internal const string ProviderConnectionName = "byok";

    public void Configure(string agentName, SessionOptionsBase config, IServiceProvider services)
    {
        var o = options.Get(agentName);

        if (o.Model is not null)
        {
            config.Model = o.Model;
        }

        if (o.ReasoningEffort is not null)
        {
            config.Inference.ReasoningEffort = o.ReasoningEffort;
        }

        if (!string.IsNullOrWhiteSpace(o.Instructions))
        {
            config.SystemMessage = new SystemMessageConfig { Mode = SystemMessageMode.Append, Content = o.Instructions };
        }

        if (o.ToolSearch is { } toolSearch && (toolSearch.Enabled is not null || toolSearch.DeferThreshold is not null))
        {
            config.Tools.Search = new ToolSearchConfig { Enabled = toolSearch.Enabled, DeferThreshold = toolSearch.DeferThreshold };
        }

        if (o.Provider is not null)
        {
            ApplyProvider(config, o.Provider);
        }

        if (o.McpServers.Count > 0)
        {
            var servers = config.Mcp.Servers ?? new Dictionary<string, McpServerConfig>(StringComparer.OrdinalIgnoreCase);
            foreach (var (name, server) in o.McpServers)
            {
                if (server is not null && CopilotMcpServerMapper.Map(server) is { } mapped)
                {
                    servers[name] = mapped;
                }
            }

            config.Mcp.Servers = servers;
        }
    }

    /// <summary>
    /// Routes every model of the session to <paramref name="provider"/>: registers it as the
    /// <see cref="ProviderConnectionName"/> connection, makes it the default for unqualified model names, and turns
    /// off Copilot API (CAPI) models. The provider's model settings (<see cref="ProviderConfig.ModelId"/>,
    /// <see cref="ProviderConfig.WireModel"/>, token limits) become a model definition for the session's model.
    /// </summary>
    internal static void ApplyProvider(SessionOptionsBase config, ProviderConfig provider)
    {
        config.Inference.Providers =
        [
            new NamedProviderConfig
            {
                Name = ProviderConnectionName,
                Type = provider.Type,
                WireApi = provider.WireApi,
                Transport = provider.Transport,
                ModelProvider = provider.ModelProvider,
                BaseUrl = provider.BaseUrl,
                ApiKey = provider.ApiKey,
                ApiKeyCommand = provider.ApiKeyCommand,
                BearerToken = provider.BearerToken,
                BearerTokenProvider = provider.BearerTokenProvider,
                Azure = provider.Azure,
                Headers = provider.Headers,
            },
        ];
        config.Inference.DefaultProvider = ProviderConnectionName;

        var hasModelSettings = provider.ModelId is not null || provider.WireModel is not null ||
            provider.MaxPromptTokens is not null || provider.MaxOutputTokens is not null;
        if (hasModelSettings && !string.IsNullOrWhiteSpace(config.Model) && !config.Model.Contains('/', StringComparison.Ordinal))
        {
            config.Inference.Models =
            [
                new ProviderModelConfig
                {
                    Provider = ProviderConnectionName,
                    Id = config.Model,
                    ModelId = provider.ModelId,
                    WireModel = provider.WireModel,
                    MaxPromptTokens = provider.MaxPromptTokens,
                    MaxOutputTokens = provider.MaxOutputTokens,
                },
            ];
        }

        config.GitHub.Capi ??= new CapiSessionOptions();
        config.GitHub.Capi.Enabled = false;
    }
}
