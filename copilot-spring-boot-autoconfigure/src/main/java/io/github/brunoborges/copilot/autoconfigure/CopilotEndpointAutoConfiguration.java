package io.github.brunoborges.copilot.autoconfigure;

import com.github.copilot.CopilotClient;
import io.github.brunoborges.copilot.actuate.CopilotEndpoint;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@AutoConfiguration(after = {CopilotAutoConfiguration.class, CopilotAgentsAutoConfiguration.class})
@ConditionalOnClass({CopilotClient.class, Endpoint.class, ConditionalOnAvailableEndpoint.class})
@ConditionalOnBean(CopilotClient.class)
@ConditionalOnProperty(prefix = "copilot", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CopilotEndpointProperties.class)
public class CopilotEndpointAutoConfiguration {

    // Nested so the actuator-only condition is parsed only when actuator auto-configuration is present.
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnAvailableEndpoint(CopilotEndpoint.class)
    static class CopilotEndpointConfiguration {

        @Bean
        @ConditionalOnMissingBean
        CopilotEndpoint copilotEndpoint(CopilotClient client, CopilotEndpointProperties properties,
                ListableBeanFactory beans, Environment environment) {
            return new CopilotEndpoint(client, properties, beans, environment);
        }
    }
}
