package io.github.brunoborges.copilot.autoconfigure;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.github.copilot.rpc.AzureOptions;
import com.github.copilot.rpc.ProviderConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

/**
 * Named BYOK (bring your own key) model providers; agents must explicitly select the provider they use.
 * Sessions using a provider send model requests directly to it instead of through GitHub Copilot.
 * @param providers provider definitions keyed by provider name
 */
@ConfigurationProperties("copilot")
public record CopilotProviderProperties(Map<String, Provider> providers) {

    public CopilotProviderProperties {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        providers.keySet().forEach(name -> Assert.isTrue(name.matches("[A-Za-z][A-Za-z0-9._-]*"),
                "Provider names must match [A-Za-z][A-Za-z0-9._-]*"));
    }

    /** SDK provider type. */
    public enum Type {
        /** OpenAI or any OpenAI-compatible endpoint, such as Ollama, Foundry Local, or Foundry {@code /openai/v1/}. */
        OPENAI,
        /** Native Azure OpenAI endpoint. */
        AZURE,
        /** Anthropic Messages API. */
        ANTHROPIC
    }

    /** OpenAI wire format; Anthropic providers always use the Messages API. */
    public enum WireApi {
        /** Chat Completions API. */
        COMPLETIONS,
        /** Responses API. */
        RESPONSES
    }

    /** @param apiVersion Azure API version; omitted uses the versionless {@code v1} route */
    public record Azure(String apiVersion) {

        public Azure {
            Assert.isTrue(apiVersion == null || !apiVersion.isBlank(), "Provider azure.api-version must not be blank");
        }
    }

    /**
     * Provider endpoint and credentials. At most one of {@code apiKey}, {@code apiKeyCommand}, and
     * {@code bearerToken} may be set; none is valid for local providers such as Ollama.
     *
     * @param type provider type
     * @param baseUrl absolute HTTP(S) endpoint URL
     * @param apiKey static API key
     * @param apiKeyCommand command the runtime runs before each provider request to obtain an API key
     * @param bearerToken static bearer token sent as the {@code Authorization} header
     * @param wireApi OpenAI wire format; not applicable to Anthropic
     * @param azure Azure options; only applicable to the Azure type
     * @param headers additional request headers
     * @param maxPromptTokens optional prompt token limit
     * @param maxOutputTokens optional output token limit
     */
    public record Provider(
            @DefaultValue("openai") Type type,
            String baseUrl,
            String apiKey,
            String apiKeyCommand,
            String bearerToken,
            WireApi wireApi,
            Azure azure,
            Map<String, String> headers,
            Integer maxPromptTokens,
            Integer maxOutputTokens) {

        public Provider {
            Assert.notNull(type, "Provider type is required");
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            headers.keySet().forEach(key -> Assert.hasText(key, "Provider header names must not be blank"));
            Assert.hasText(baseUrl, "A provider requires a base-url");
            String message = "Provider base-url must be an absolute HTTP(S) address without credentials or a fragment";
            URI address;
            try {
                address = URI.create(baseUrl);
            } catch (IllegalArgumentException ex) {
                // URI parser messages echo the input, which may contain credentials or tokens.
                throw new IllegalArgumentException(message);
            }
            Assert.isTrue(("http".equalsIgnoreCase(address.getScheme()) || "https".equalsIgnoreCase(address.getScheme()))
                            && address.getHost() != null && address.getRawUserInfo() == null
                            && address.getRawFragment() == null
                            && (address.getPort() == -1 || (address.getPort() >= 1 && address.getPort() <= 65535)),
                    message);
            Assert.isTrue(Stream.of(apiKey, apiKeyCommand, bearerToken).allMatch(value -> value == null || !value.isBlank()),
                    "Provider api-key, api-key-command, and bearer-token must not be blank");
            Assert.isTrue(Stream.of(apiKey, apiKeyCommand, bearerToken).filter(value -> value != null).count() <= 1,
                    "Provider api-key, api-key-command, and bearer-token are mutually exclusive");
            Assert.isTrue(type != Type.ANTHROPIC || wireApi == null, "Anthropic providers cannot declare wire-api");
            Assert.isTrue(type == Type.AZURE || azure == null, "Only Azure providers can declare azure options");
            Assert.isTrue(maxPromptTokens == null || maxPromptTokens > 0, "Provider max-prompt-tokens must be positive");
            Assert.isTrue(maxOutputTokens == null || maxOutputTokens > 0, "Provider max-output-tokens must be positive");
        }

        /** Creates a fresh SDK provider configuration without contacting the provider. */
        public ProviderConfig toProviderConfig() {
            var config = new ProviderConfig().setType(type.name().toLowerCase(Locale.ROOT)).setBaseUrl(baseUrl)
                    .setApiKey(apiKey).setApiKeyCommand(apiKeyCommand).setBearerToken(bearerToken);
            if (wireApi != null) {
                config.setWireApi(wireApi.name().toLowerCase(Locale.ROOT));
            }
            if (azure != null && azure.apiVersion() != null) {
                config.setAzure(new AzureOptions().setApiVersion(azure.apiVersion()));
            }
            if (!headers.isEmpty()) {
                config.setHeaders(headers);
            }
            if (maxPromptTokens != null) {
                config.setMaxPromptTokens(maxPromptTokens);
            }
            if (maxOutputTokens != null) {
                config.setMaxOutputTokens(maxOutputTokens);
            }
            return config;
        }

        @Override
        public String toString() {
            return "Provider[type=" + type + "]";
        }
    }
}
