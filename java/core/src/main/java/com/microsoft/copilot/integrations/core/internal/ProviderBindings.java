package com.microsoft.copilot.integrations.core.internal;

import java.util.List;

import com.microsoft.copilot.AllowCopilotExperimental;
import com.microsoft.copilot.rpc.CapiSessionOptions;
import com.microsoft.copilot.rpc.NamedProviderConfig;
import com.microsoft.copilot.rpc.ProviderConfig;
import com.microsoft.copilot.rpc.ProviderModelConfig;
import com.microsoft.copilot.rpc.SessionGitHubOptions;
import com.microsoft.copilot.rpc.SessionInferenceOptions;

@AllowCopilotExperimental
final class ProviderBindings {

    /** Connection name under which an agent's BYOK provider is registered in its session. */
    static final String CONNECTION_NAME = "byok";

    private ProviderBindings() {
    }

    /** Resolves a provider name to a property definition or SDK bean; an empty name selects none. */
    static ProviderConfig bind(String name, BindingContext binding) {
        if (name.isEmpty()) {
            return null;
        }
        var beans = binding.beans();
        var configured = binding.providers().apply(name);
        boolean nativeBean = beans.containsBean(name) && beans.isTypeMatch(name, ProviderConfig.class);
        Assert.isTrue(configured == null || !nativeBean,
                "Ambiguous provider definition in properties and SDK bean: " + name);
        Assert.isTrue(configured != null || nativeBean, () -> "Unknown provider '" + name + "': define "
                + binding.configPrefix() + ".providers." + name + " or an SDK ProviderConfig bean named '" + name + "'");
        return configured == null ? beans.getBean(name, ProviderConfig.class) : configured;
    }

    /**
     * Routes every model request through the agent's provider, as the former single session provider did: the
     * provider becomes the default connection, Copilot API models are disabled, and model-specific provider settings
     * are registered as a model definition for {@code model}.
     */
    static void apply(ProviderConfig provider, String model, SessionInferenceOptions inference,
            SessionGitHubOptions github) {
        var connection = new NamedProviderConfig().setName(CONNECTION_NAME).setType(provider.getType())
                .setWireApi(provider.getWireApi()).setTransport(provider.getTransport())
                .setModelProvider(provider.getModelProvider()).setBaseUrl(provider.getBaseUrl())
                .setApiKey(provider.getApiKey()).setApiKeyCommand(provider.getApiKeyCommand())
                .setBearerToken(provider.getBearerToken()).setBearerTokenProvider(provider.getBearerTokenProvider())
                .setAzure(provider.getAzure()).setHeaders(provider.getHeaders());
        inference.setProviders(List.of(connection)).setDefaultProvider(CONNECTION_NAME);
        if (provider.getModelId() != null || provider.getWireModel() != null
                || provider.getMaxPromptTokens().isPresent() || provider.getMaxOutputTokens().isPresent()) {
            var definition = new ProviderModelConfig().setProvider(CONNECTION_NAME).setId(model)
                    .setModelId(provider.getModelId()).setWireModel(provider.getWireModel());
            provider.getMaxPromptTokens().ifPresent(definition::setMaxPromptTokens);
            provider.getMaxOutputTokens().ifPresent(definition::setMaxOutputTokens);
            inference.setModels(List.of(definition));
        } else {
            inference.setModels(List.of());
        }
        var capi = github.getCapi() == null ? new CapiSessionOptions() : github.getCapi();
        github.setCapi(capi.setEnabled(false));
    }
}
