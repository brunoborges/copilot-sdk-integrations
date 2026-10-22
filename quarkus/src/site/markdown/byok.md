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
The extension maps provider definitions to the SDK's native `ProviderConfig`.
See the SDK's [BYOK documentation](https://github.com/github/copilot-sdk/blob/main/docs/auth/byok.md)
for provider-specific behavior.

<a id="define-providers-in-configuration"></a>

## Define providers in configuration

```ini
quarkus.copilot.providers.foundry.type=openai
quarkus.copilot.providers.foundry.base-url=https://my-resource.openai.azure.com/openai/v1/
quarkus.copilot.providers.foundry.api-key=${FOUNDRY_API_KEY}
quarkus.copilot.providers.foundry.wire-api=responses

quarkus.copilot.providers.azure.type=azure
quarkus.copilot.providers.azure.base-url=https://my-resource.openai.azure.com
quarkus.copilot.providers.azure.api-key=${AZURE_OPENAI_KEY}
quarkus.copilot.providers.azure.azure-api-version=2024-10-21

quarkus.copilot.providers.claude.type=anthropic
quarkus.copilot.providers.claude.base-url=https://api.anthropic.com
quarkus.copilot.providers.claude.api-key=${ANTHROPIC_API_KEY}

quarkus.copilot.providers.ollama.base-url=http://localhost:11434/v1
```

Provider names must match `[A-Za-z][A-Za-z0-9._-]*`. `base-url` is required for
every defined provider. Definitions are validated at startup, when agents are
initialized, even if no agent selects them; validation never contacts the
provider. The Azure API version
is the flat key `azure-api-version` (Spring Boot nests it as `azure.api-version`).
See [BYOK properties](configuration.html#byok-properties) for every field and
its validation.

<a id="select-a-provider-per-agent"></a>

## Select a provider per agent

```java
@CopilotAgent(name = "research-agent", model = "gpt-5.2-codex", provider = "foundry")
public interface ResearchAgent {
    CompletableFuture<String> answer(@ConversationId String id, @UserMessage String question);
}
```

The SDK requires a model with a provider, so selecting a provider without a
model fails initialization. Use the model or deployment name your provider
expects. Agents without a provider keep using GitHub Copilot.

Per-agent properties replace the annotation selection, for example to use a
local model in development only, with a Quarkus profile prefix:

```ini
%dev.quarkus.copilot.agents.research-agent.model=llama3.2
%dev.quarkus.copilot.agents.research-agent.provider=ollama
```

`provider=none` selects no provider, and an omitted value keeps the annotation
selection. (Quarkus treats an empty value as unset, so use `none` rather than
an empty string.) Missing or ambiguous references fail initialization.

<a id="native-sdk-beans-and-sessions"></a>

## Native SDK beans and sessions

A provider name may also refer to an SDK `ProviderConfig` CDI bean with a
matching `@Named` qualifier. Use this for settings that properties cannot
express, such as a `BearerTokenProvider` that acquires short-lived Microsoft
Entra tokens (this example uses the `azure-identity` library):

```java
@Singleton
public class ProviderConfiguration {

    @Produces
    @Singleton
    @Named("entra")
    ProviderConfig entra() {
        var credential = new DefaultAzureCredentialBuilder().build();
        var scope = new TokenRequestContext().addScopes("https://cognitiveservices.azure.com/.default");
        return new ProviderConfig()
                .setType("openai")
                .setBaseUrl("https://my-resource.openai.azure.com/openai/v1/")
                .setWireApi("responses")
                .setBearerTokenProvider(args -> credential.getToken(scope).map(AccessToken::getToken).toFuture());
    }
}
```

A name defined both as a property and as a `ProviderConfig` bean fails
initialization. The selected provider is set on both created and resumed
sessions, because the SDK does not persist provider credentials. Customizers
run afterwards and may replace it. Code that creates native SDK sessions can
call `CopilotConfigs.providers(config).providers().get("foundry").toProviderConfig()`
(with an injected `CopilotRuntimeConfig`) to get a fresh configuration.

<a id="security-and-limitations"></a>

## Security and limitations

- Supply keys and tokens through environment variables or a secret-backed
  config source. Never commit them. `api-key`, `api-key-command`, and
  `bearer-token` are mutually exclusive, and the provider definitions'
  `toString()` omits them.
- `api-key-command` runs a command in the runtime before each provider request.
  Treat it like any other configured executable.
- `base-url` must be an absolute HTTP(S) URL without embedded credentials or a
  fragment. Validation errors do not echo the configured value.
- Usage, rate limits, and model availability come from your provider, not
  GitHub Copilot. Some Copilot features may behave differently; see the SDK's
  BYOK limitations.
- Permission handling is unchanged: selecting a provider grants no tool permissions.
- In a BYOK-only application the runtime may be unauthenticated with GitHub; set
  `quarkus.copilot.health.require-authentication=false` so the readiness check
  does not report `DOWN` (see [Health, info, and endpoint](management.html)).

---

**Previous:** [MCP servers](mcp.html)

**Next:** [Per-user authentication](authentication.html)
