package io.github.brunoborges.copilot.autoconfigure;

import io.github.brunoborges.copilot.agent.CopilotErrorRecoveryHooks;
import io.github.brunoborges.copilot.agent.GlobalCopilotAgentHooks;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Registers {@link CopilotErrorRecoveryHooks} for every agent when {@code copilot.error-recovery.enabled=true}. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "copilot", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "copilot.error-recovery", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CopilotErrorRecoveryProperties.class)
public class CopilotErrorRecoveryAutoConfiguration {

    @Bean
    @GlobalCopilotAgentHooks
    @ConditionalOnMissingBean(CopilotErrorRecoveryHooks.class)
    CopilotErrorRecoveryHooks copilotErrorRecoveryHooks(CopilotErrorRecoveryProperties properties) {
        return properties.toHooks();
    }
}
