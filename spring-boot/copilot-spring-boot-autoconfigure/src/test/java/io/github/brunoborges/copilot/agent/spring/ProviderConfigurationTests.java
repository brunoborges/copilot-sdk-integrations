package io.github.brunoborges.copilot.agent.spring;

import io.github.brunoborges.copilot.agent.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.ProviderConfig;
import io.github.brunoborges.copilot.agent.fixtures.TestAgent;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsAutoConfiguration;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsProperties;
import io.github.brunoborges.copilot.autoconfigure.CopilotAutoConfiguration;
import io.github.brunoborges.copilot.autoconfigure.CopilotProviderProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ProviderConfigurationTests {

    private final CopilotClient client = mock(CopilotClient.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CopilotAutoConfiguration.class, CopilotAgentsAutoConfiguration.class))
            .withBean(CopilotClient.class, () -> client)
            .withInitializer(context -> {
                if (context.getBeanFactory() instanceof BeanDefinitionRegistry registry) {
                    AutoConfigurationPackages.register(registry, TestAgent.class.getPackageName());
                } else {
                    throw new IllegalStateException("Test context requires a bean definition registry");
                }
            });

    @Test
    void bindsProvidersAndPerAgentSelectionWithoutConnecting() {
        runner.withPropertyValues(
                "copilot.providers.foundry.base-url=https://example.openai.azure.com/openai/v1/",
                "copilot.providers.foundry.api-key=secret-key",
                "copilot.providers.foundry.wire-api=responses",
                "copilot.providers.foundry.headers[X-Team]=docs",
                "copilot.providers.foundry.max-prompt-tokens=1000",
                "copilot.providers.foundry.max-output-tokens=200",
                "copilot.providers.azure.type=azure",
                "copilot.providers.azure.base-url=https://example.openai.azure.com",
                "copilot.providers.azure.bearer-token=secret-token",
                "copilot.providers.azure.azure.api-version=2024-10-21",
                "copilot.providers.claude.type=anthropic",
                "copilot.providers.claude.base-url=https://api.anthropic.com",
                "copilot.providers.claude.api-key-command=/usr/local/bin/get-key",
                "copilot.providers.ollama.base-url=http://localhost:11434/v1",
                "copilot.agents.test-agent.model=gpt-5.2-codex",
                "copilot.agents.test-agent.provider=foundry").run(context -> {
                    assertThat(context).hasNotFailed();
                    var providers = context.getBean(CopilotProviderProperties.class).providers();
                    var foundry = providers.get("foundry").toProviderConfig();
                    assertThat(foundry.getType()).isEqualTo("openai");
                    assertThat(foundry.getBaseUrl()).isEqualTo("https://example.openai.azure.com/openai/v1/");
                    assertThat(foundry.getApiKey()).isEqualTo("secret-key");
                    assertThat(foundry.getWireApi()).isEqualTo("responses");
                    assertThat(foundry.getHeaders()).containsEntry("X-Team", "docs");
                    assertThat(foundry.getMaxPromptTokens()).hasValue(1000);
                    assertThat(foundry.getMaxOutputTokens()).hasValue(200);
                    var azure = providers.get("azure").toProviderConfig();
                    assertThat(azure.getType()).isEqualTo("azure");
                    assertThat(azure.getBearerToken()).isEqualTo("secret-token");
                    assertThat(azure.getAzure().getApiVersion()).isEqualTo("2024-10-21");
                    var claude = providers.get("claude").toProviderConfig();
                    assertThat(claude.getType()).isEqualTo("anthropic");
                    assertThat(claude.getApiKeyCommand()).isEqualTo("/usr/local/bin/get-key");
                    var ollama = providers.get("ollama").toProviderConfig();
                    assertThat(ollama.getApiKey()).isNull();
                    assertThat(ollama.getWireApi()).isNull();
                    assertThat(ollama.getHeaders()).isNull();
                    assertThat(ollama.getMaxPromptTokens()).isEmpty();
                    assertThat(providers.get("foundry").toProviderConfig()).isNotSameAs(foundry);
                    assertThat(providers.get("foundry").toString()).doesNotContain("secret-key");
                    assertThat(providers.get("azure").toString()).doesNotContain("secret-token");
                    assertThat(context.getBean(CopilotAgentsProperties.class).agents().get("test-agent").provider())
                            .isEqualTo("foundry");
                    verifyNoInteractions(client);
                });
    }

    @Test
    void unknownProviderNamesBothLookupLocations() {
        runner.withPropertyValues("copilot.agents.test-agent.model=m", "copilot.agents.test-agent.provider=missing")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining(
                        "define copilot.providers.missing or an SDK ProviderConfig bean named 'missing'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "copilot.providers.local.type=unknown",
            "copilot.providers.local.base-url=",
            "copilot.providers.local.api-key= ",
            "copilot.providers.local.api-key=a,copilot.providers.local.bearer-token=b",
            "copilot.providers.local.api-key=a,copilot.providers.local.api-key-command=b",
            "copilot.providers.local.type=anthropic,copilot.providers.local.wire-api=responses",
            "copilot.providers.local.wire-api=unknown",
            "copilot.providers.local.azure.api-version=2024-10-21",
            "copilot.providers.local.type=azure,copilot.providers.local.azure.api-version= ",
            "copilot.providers.local.headers[ ]=value",
            "copilot.providers.local.max-prompt-tokens=0",
            "copilot.providers.local.max-output-tokens=-1",
            "copilot.providers.1local.base-url=http://localhost:11434/v1",
            "copilot.agents.test-agent.model=m,copilot.agents.test-agent.provider=missing",
            "copilot.agents.test-agent.provider=local"
    })
    void rejectsInvalidConfiguration(String invalid) {
        runner.withPropertyValues("copilot.providers.local.base-url=http://localhost:11434/v1")
                .withPropertyValues(invalid.split(","))
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/relative", "ftp://example.com/v1", "https://user:pass@example.com/v1",
            "https://example.com/v1#fragment", "http://localhost:0/v1", "http://localhost:65536/v1"})
    void rejectsInvalidBaseUrls(String url) {
        runner.withPropertyValues("copilot.providers.remote.base-url=" + url)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void malformedBaseUrlFailureDoesNotEchoSecrets() {
        runner.withPropertyValues("copilot.providers.remote.base-url=https://example.com/v1?key=secret-value with space")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("Provider base-url must be an absolute HTTP(S) address")
                        .satisfies(failure -> assertThat(stackTrace(failure)).doesNotContain("secret-value")));
    }

    @Test
    void selectsNativeProviderBeansAndRejectsAmbiguity() {
        var provider = new ProviderConfig().setType("openai").setBaseUrl("http://localhost:11434/v1");
        runner.withBean("custom", ProviderConfig.class, () -> provider)
                .withPropertyValues("copilot.agents.test-agent.model=m", "copilot.agents.test-agent.provider=custom")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withBean("local", ProviderConfig.class, () -> provider)
                .withPropertyValues("copilot.providers.local.base-url=http://localhost:11434/v1",
                        "copilot.agents.test-agent.model=m", "copilot.agents.test-agent.provider=local")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("Ambiguous provider definition"));
    }

    @Test
    void providerRequiresModelAndEmptySelectionIsDistinctFromAbsentOverride() {
        runner.withPropertyValues("copilot.providers.local.base-url=http://localhost:11434/v1",
                        "copilot.agents.test-agent.provider=local")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("a BYOK provider requires a model"));
        runner.withPropertyValues("copilot.agents.test-agent.provider=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CopilotAgentsProperties.class).agents().get("test-agent").provider()).isEmpty();
        });
        assertThat(new CopilotAgentsProperties.Agent(null, null, Duration.ofSeconds(60)).provider()).isNull();
        assertThatThrownBy(() -> new CopilotAgentsProperties.Agent(null, null, Duration.ofSeconds(60), null, " "))
                .hasMessageContaining("Agent provider must not be blank");
    }

    private static String stackTrace(Throwable failure) {
        var writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
