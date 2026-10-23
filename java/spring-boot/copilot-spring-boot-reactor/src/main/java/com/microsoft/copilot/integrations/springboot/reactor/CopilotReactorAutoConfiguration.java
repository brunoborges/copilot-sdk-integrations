package com.microsoft.copilot.integrations.springboot.reactor;

import com.github.copilot.CopilotClient;
import com.microsoft.copilot.integrations.springboot.autoconfigure.CopilotAgentsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

@AutoConfiguration(before = CopilotAgentsAutoConfiguration.class)
@ConditionalOnClass({CopilotClient.class, Flux.class})
@ConditionalOnProperty(prefix = "copilot", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CopilotReactorProperties.class)
public class CopilotReactorAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ReactorCopilotAgentAdapter copilotReactorReturnTypeAdapter(CopilotReactorProperties properties) {
        return new ReactorCopilotAgentAdapter(properties);
    }
}
