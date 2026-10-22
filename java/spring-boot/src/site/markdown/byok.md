---
title: BYOK providers
---

<a id="byok-providers"></a>

# BYOK providers

**In this chapter**

- [Define providers in configuration](#define-providers-in-configuration)
- [Select a provider per agent](#select-a-provider-per-agent)
- [Native SDK beans and sessions](#native-sdk-beans-and-sessions)
- [Security and limitations](#security-and-limitations)

BYOK (bring your own key) sends an agent's model requests directly to your own
model provider, such as OpenAI, Azure OpenAI / Microsoft Foundry, Anthropic,
Ollama, or another OpenAI-compatible endpoint. Model requests do not go
through GitHub Copilot and do not consume Copilot usage. A provider applies per session.
The integration maps provider definitions to the SDK's native `ProviderConfig`.
See the SDK's [BYOK documentation](https://github.com/github/copilot-sdk/blob/main/docs/auth/byok.md)
for provider-specific behavior.

<a id="define-providers-in-configuration"></a>

## Define providers in configuration

```yaml
copilot:
  providers:
    foundry:
      type: openai
      base-url: https://my-resource.openai.azure.com/openai/v1/
      api-key: ${FOUNDRY_API_KEY}
      wire-api: responses
    azure:
      type: azure
      base-url: https://my-resource.openai.azure.com
      api-key: ${AZURE_OPENAI_KEY}
      azure:
        api-version: 2024-10-21
    claude:
      type: anthropic
      base-url: https://api.anthropic.com
      api-key: ${ANTHROPIC_API_KEY}
    ollama:
      base-url: http://localhost:11434/v1
```

Provider names must match `[A-Za-z][A-Za-z0-9._-]*`. Definitions are validated
at startup even when no agent selects them, and binding never contacts the
provider. See [BYOK properties](configuration.html#byok-properties) for every
field and its validation.

<a id="select-a-provider-per-agent"></a>

## Select a provider per agent

```java
@CopilotAgent(model = "gpt-5.2-codex", provider = "foundry")
public interface ResearchAgent {
    CompletableFuture<String> answer(@ConversationId String id, @UserMessage String question);
}
```

The SDK requires a model with a provider, so selecting a provider without a
model fails initialization. Use the model or deployment name your provider
expects. Agents without a provider keep using GitHub Copilot.

Per-agent properties replace the annotation selection, for example to use a
local model in development:

```yaml
copilot:
  agents:
    research-agent:
      model: llama3.2
      provider: ollama
```

An empty `provider` value selects no provider, and an omitted value keeps the
annotation selection. Missing or ambiguous references fail initialization.

<a id="native-sdk-beans-and-sessions"></a>

## Native SDK beans and sessions

A provider name may also refer to an SDK `ProviderConfig` bean. Use this for
settings that properties cannot express, such as a `BearerTokenProvider` that
acquires short-lived Microsoft Entra tokens (this example uses the
`azure-identity` library):

```java
@Bean
ProviderConfig entra(TokenCredential credential) {
    var scope = new TokenRequestContext().addScopes("https://cognitiveservices.azure.com/.default");
    return new ProviderConfig()
            .setType("openai")
            .setBaseUrl("https://my-resource.openai.azure.com/openai/v1/")
            .setWireApi("responses")
            .setBearerTokenProvider(args -> credential.getToken(scope).map(AccessToken::getToken).toFuture());
}
```

A name defined both as a property and as a `ProviderConfig` bean fails
initialization. The selected provider is set on both created and resumed
sessions, because the SDK does not persist provider credentials. Customizers
run afterwards and may replace it. Code that creates
native SDK sessions can call `CopilotProviderProperties.Provider.toProviderConfig()`
to get a fresh configuration.

<a id="security-and-limitations"></a>

## Security and limitations

- Supply keys and tokens through environment variables or a secret store.
  Never commit them. `api-key`, `api-key-command`, and `bearer-token` are
  mutually exclusive, and the provider definitions' `toString()` omits them.
- `api-key-command` runs a command in the runtime before each provider request.
  Treat it like any other configured executable.
- `base-url` must be an absolute HTTP(S) URL without embedded credentials or a
  fragment. Validation errors do not echo the configured value.
- Usage, rate limits, and model availability come from your provider, not
  GitHub Copilot. Some Copilot features may behave differently; see the SDK's
  BYOK limitations.
- Permission handling is unchanged: selecting a provider grants no tool permissions.

---

**Previous:** [MCP servers](mcp.html)

**Next:** [Per-user authentication](authentication.html)
