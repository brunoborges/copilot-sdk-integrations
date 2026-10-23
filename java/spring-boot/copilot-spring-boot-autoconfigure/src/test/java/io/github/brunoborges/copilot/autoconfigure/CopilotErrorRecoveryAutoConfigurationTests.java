package io.github.brunoborges.copilot.autoconfigure;

import java.util.List;

import com.github.copilot.rpc.PostToolUseFailureHookInput;
import io.github.brunoborges.copilot.agent.CopilotAgentContext;
import io.github.brunoborges.copilot.agent.CopilotErrorRecoveryHooks;
import io.github.brunoborges.copilot.autoconfigure.internal.SpringBeanLookup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotErrorRecoveryAutoConfigurationTests {

    private static final CopilotAgentContext CONTEXT = new CopilotAgentContext("agent", "c1", "s1");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CopilotErrorRecoveryAutoConfiguration.class));

    @Test
    void isOffByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(CopilotErrorRecoveryHooks.class));
        runner.withPropertyValues("copilot.enabled=false", "copilot.error-recovery.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(CopilotErrorRecoveryHooks.class));
    }

    @Test
    void registersAGlobalHookWhenEnabled() {
        runner.withPropertyValues("copilot.error-recovery.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(CopilotErrorRecoveryHooks.class);
            var hooks = context.getBean(CopilotErrorRecoveryHooks.class);
            assertThat(new SpringBeanLookup(context.getBeanFactory()).globalHooks()).containsExactly(hooks);
            assertThat(hooks.classify("permission denied"))
                    .isEqualTo(CopilotErrorRecoveryHooks.Category.PERMISSION_DENIED);
        });
    }

    @Test
    void appliesProperties() {
        runner.withPropertyValues("copilot.error-recovery.enabled=true",
                "copilot.error-recovery.permission-phrases=not authorized",
                "copilot.error-recovery.shell-error-phrases=fatal:",
                "copilot.error-recovery.error-note=keep going",
                "copilot.error-recovery.max-consecutive-failures=1").run(context -> {
            var hooks = context.getBean(CopilotErrorRecoveryHooks.class);
            assertThat(hooks.classify("permission denied")).isEqualTo(CopilotErrorRecoveryHooks.Category.NORMAL);
            assertThat(hooks.classify("fatal: bad")).isEqualTo(CopilotErrorRecoveryHooks.Category.SHELL_ERROR);
            assertThat(hooks.onPostToolUseFailure(CONTEXT, failure()).getAdditionalContext()).isEqualTo("keep going");
            assertThat(hooks.onPostToolUseFailure(CONTEXT, failure())).isNull();
        });
    }

    @Test
    void rejectsANegativeCap() {
        runner.withPropertyValues("copilot.error-recovery.enabled=true",
                "copilot.error-recovery.max-consecutive-failures=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void backsOffForAnApplicationBean() {
        runner.withPropertyValues("copilot.error-recovery.enabled=true").withUserConfiguration(Custom.class)
                .run(context -> assertThat(context).hasSingleBean(CopilotErrorRecoveryHooks.class)
                        .hasBean("customRecovery"));
    }

    private static PostToolUseFailureHookInput failure() {
        return new PostToolUseFailureHookInput().setSessionId("s1").setToolName("bash").setError("boom");
    }

    @Configuration(proxyBeanMethods = false)
    static class Custom {

        @Bean
        CopilotErrorRecoveryHooks customRecovery() {
            return CopilotErrorRecoveryHooks.builder().permissionPhrases(List.of("nope")).build();
        }
    }
}
