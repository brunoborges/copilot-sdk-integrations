package io.github.brunoborges.copilot.agent;

import java.time.Duration;

import com.github.copilot.CopilotClient;
import io.github.brunoborges.copilot.agent.fixtures.TestAgent;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsAutoConfiguration;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsProperties;
import io.github.brunoborges.copilot.autoconfigure.CopilotAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CopilotAgentsAutoConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CopilotAutoConfiguration.class, CopilotAgentsAutoConfiguration.class))
            .withInitializer(context -> {
                if (context.getBeanFactory() instanceof org.springframework.beans.factory.support.BeanDefinitionRegistry registry) {
                    AutoConfigurationPackages.register(registry, TestAgent.class.getPackageName());
                } else {
                    throw new IllegalStateException("Test context requires a bean definition registry");
                }
            });

    @Test
    void discoversAndInjectsAgentWithoutOpeningConnection() {
        var client = mock(CopilotClient.class);
        runner.withBean(CopilotClient.class, () -> client).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TestAgent.class).hasSingleBean(CopilotAgentInvoker.class);
            assertThat(context.getBean("test-agent")).isSameAs(context.getBean(TestAgent.class));
            verifyNoInteractions(client);
        });
        verify(client).close();
    }

    @Test
    void explicitScanningWorksOutsideTheApplicationPackage() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CopilotAutoConfiguration.class, CopilotAgentsAutoConfiguration.class))
                .withUserConfiguration(ExplicitScan.class).run(context ->
                        assertThat(context).hasNotFailed().hasSingleBean(TestAgent.class));
    }

    @Test
    void disabledIntegrationDoesNotRegisterAgents() {
        runner.withPropertyValues("copilot.enabled=false").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(TestAgent.class).doesNotHaveBean(CopilotAgentInvoker.class));
    }

    @Test
    void duplicateBeanNameFailsInsteadOfReplacingExistingBean() {
        runner.withBean("test-agent", String.class, () -> "existing").run(context ->
                assertThat(context).hasFailed().getFailure().hasStackTraceContaining("Duplicate Copilot agent bean name"));
    }

    @Test
    void missingSdkBacksOff() {
        runner.withClassLoader(new FilteredClassLoader("com.github.copilot")).run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(CopilotAgentsAutoConfiguration.class));
    }

    @Test
    void bindsPerAgentSettings() {
        runner.withPropertyValues("copilot.agents.test-agent.model=custom-model",
                "copilot.agents.test-agent.reasoning-effort=high", "copilot.agents.test-agent.response-timeout=5s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var settings = context.getBean(CopilotAgentsProperties.class).agents().get("test-agent");
                    assertThat(settings.model()).isEqualTo("custom-model");
                    assertThat(settings.reasoningEffort()).isEqualTo("high");
                    assertThat(settings.responseTimeout()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "1ns", "invalid"})
    void rejectsInvalidTimeouts(String timeout) {
        runner.withPropertyValues("copilot.agents.test-agent.response-timeout=" + timeout).run(context ->
                assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCopilotAgents(basePackageClasses = TestAgent.class)
    static class ExplicitScan {
    }
}
