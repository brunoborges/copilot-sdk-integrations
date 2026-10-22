package io.github.brunoborges.copilot.agent.spring;

import io.github.brunoborges.copilot.agent.*;


import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.PreToolUseHookInput;
import com.github.copilot.rpc.PreToolUseHookOutput;
import io.github.brunoborges.copilot.agent.fixtures.TestAgent;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsAutoConfiguration;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsProperties;
import io.github.brunoborges.copilot.autoconfigure.CopilotAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GovernanceAutoConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CopilotAutoConfiguration.class, CopilotAgentsAutoConfiguration.class))
            .withBean(CopilotClient.class, () -> mock(CopilotClient.class))
            .withInitializer(context -> {
                if (context.getBeanFactory() instanceof org.springframework.beans.factory.support.BeanDefinitionRegistry registry) {
                    AutoConfigurationPackages.register(registry, TestAgent.class.getPackageName());
                }
            });

    @Test
    void bindsGovernanceProperties() {
        runner.withPropertyValues("copilot.agents.test-agent.available-tools=view,custom:*",
                "copilot.agents.test-agent.excluded-built-in-agents=explore",
                "copilot.agents.test-agent.built-in-tools=true",
                "copilot.agents.test-agent.working-directory=/srv/work",
                "copilot.agents.test-agent.additional-directories[0]=/srv/a",
                "copilot.agents.test-agent.permissions.allow=read,url",
                "copilot.agents.test-agent.permissions.allow-tools=echo",
                "copilot.agents.test-agent.permissions.allow-mcp-servers=docs",
                "copilot.agents.test-agent.permissions.allow-agent-tools=true",
                "copilot.agents.test-agent.permissions.allow-read-only=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var agent = context.getBean(CopilotAgentsProperties.class).agents().get("test-agent");
                    assertThat(agent.availableTools()).containsExactly("view", "custom:*");
                    assertThat(agent.excludedBuiltInAgents()).containsExactly("explore");
                    assertThat(agent.builtInTools()).isTrue();
                    assertThat(agent.workingDirectory()).isEqualTo("/srv/work");
                    assertThat(agent.additionalDirectories()).containsExactly("/srv/a");
                    assertThat(agent.permissions().allow()).containsExactly("read", "url");
                    assertThat(agent.permissions().allowTools()).containsExactly("echo");
                    assertThat(agent.permissions().allowMcpServers()).containsExactly("docs");
                    assertThat(agent.permissions().allowAgentTools()).isTrue();
                    assertThat(agent.permissions().allowReadOnly()).isTrue();
                });
    }

    @Test
    void conflictingToolFiltersFailStartup() {
        runner.withPropertyValues("copilot.agents.test-agent.available-tools=view",
                "copilot.agents.test-agent.excluded-tools=bash").run(context ->
                assertThat(context).hasFailed().getFailure().hasStackTraceContaining("mutually exclusive"));
    }

    @Test
    void globalThenTargetedHookBeansAreOrderedByBeanMethodOrder() {
        runner.withUserConfiguration(HooksConfiguration.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    var definition = SpringTestSupport.definition(
                            TestAgent.class, context.getBeanFactory(), context.getEnvironment(), context,
                            context.getBean(CopilotAgentsProperties.class));
                    assertThat(definition.governance().hooks()).containsExactly(context.getBean("first",
                            CopilotAgentHooks.class), context.getBean("second", CopilotAgentHooks.class),
                            context.getBean("local", CopilotAgentHooks.class));
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class HooksConfiguration {

        @Bean
        @Order(2)
        @GlobalCopilotAgentHooks
        CopilotAgentHooks second() {
            return new Named("second");
        }

        @Bean
        @Order(1)
        @GlobalCopilotAgentHooks
        CopilotAgentHooks first() {
            return new Named("first");
        }

        @Bean
        @CopilotAgentHooksFor(TestAgent.class)
        CopilotAgentHooks local() {
            return new Named("local");
        }

        @Bean
        @CopilotAgentHooksFor(Runnable.class)
        CopilotAgentHooks elsewhere() {
            return new Named("elsewhere");
        }
    }

    record Named(String name) implements CopilotAgentHooks {
        @Override
        public PreToolUseHookOutput onPreToolUse(CopilotAgentContext context, PreToolUseHookInput input) {
            return null;
        }
    }
}
