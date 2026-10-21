package io.github.brunoborges.copilot.autoconfigure;

import com.github.copilot.CopilotClient;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration(after = CopilotAutoConfiguration.class)
@ConditionalOnClass(CopilotClient.class)
@ConditionalOnBean(CopilotClient.class)
@ConditionalOnProperty(prefix = "copilot", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CopilotAgentsProperties.class)
@Import(CopilotAgentsRegistrar.class)
public class CopilotAgentsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    CopilotAgentInvoker copilotAgentInvoker(CopilotClient client) {
        return new CopilotAgentInvoker(client);
    }
}
