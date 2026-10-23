package io.github.brunoborges.copilot.autoconfigure;

import com.github.copilot.CopilotClient;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.agent.CopilotAgentTelemetry;
import io.github.brunoborges.copilot.agent.CopilotAgents;
import io.github.brunoborges.copilot.agent.CopilotLoops;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentDefinition;
import io.github.brunoborges.copilot.agent.internal.DefaultCopilotAgents;
import io.github.brunoborges.copilot.agent.internal.DefaultCopilotLoops;
import io.github.brunoborges.copilot.autoconfigure.internal.CopilotAgentFactoryBean;
import io.github.brunoborges.copilot.autoconfigure.internal.SpringAgentBinding;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;
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
@EnableConfigurationProperties({CopilotAgentsProperties.class, CopilotAgentExecutionProperties.class, CopilotMcpProperties.class,
        CopilotProviderProperties.class})
@Import(CopilotAgentsRegistrar.class)
public class CopilotAgentsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    CopilotAgentInvoker copilotAgentInvoker(CopilotClient client, CopilotAgentTelemetry telemetry,
            CopilotAgentExecutionProperties execution) {
        return new CopilotAgentInvoker(client, telemetry, SpringAgentBinding.limits(execution),
                SpringAgentBinding.contextPropagation(CopilotAgentInvoker.class.getClassLoader()));
    }

    @Bean
    @ConditionalOnMissingBean(CopilotAgentTelemetry.class)
    CopilotAgentTelemetry copilotAgentTelemetry() {
        return CopilotAgentTelemetry.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean(CopilotAgents.class)
    CopilotAgents copilotAgents(BeanFactory beans, Environment environment) {
        return new DefaultCopilotAgents(type -> {
            String name = "&" + CopilotAgentDefinition.name(type, environment::resolveRequiredPlaceholders);
            return beans.containsBean(name) ? beans.getBean(name, CopilotAgentFactoryBean.class).factory() : null;
        });
    }

    @Bean
    @ConditionalOnMissingBean(CopilotLoops.class)
    CopilotLoops copilotLoops(CopilotAgents agents) {
        return new DefaultCopilotLoops(agents);
    }
}
