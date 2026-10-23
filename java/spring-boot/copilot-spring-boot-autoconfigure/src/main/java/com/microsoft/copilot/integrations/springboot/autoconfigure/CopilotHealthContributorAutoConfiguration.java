package com.microsoft.copilot.integrations.springboot.autoconfigure;

import com.github.copilot.CopilotClient;
import com.microsoft.copilot.integrations.springboot.actuate.CopilotHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration(after = CopilotAutoConfiguration.class)
@ConditionalOnClass({CopilotClient.class, HealthIndicator.class, ConditionalOnEnabledHealthIndicator.class})
@ConditionalOnBean(CopilotClient.class)
@ConditionalOnProperty(prefix = "copilot", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CopilotHealthProperties.class)
public class CopilotHealthContributorAutoConfiguration {

    // Nested so the health-only condition is parsed only when spring-boot-health is present.
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEnabledHealthIndicator("copilot")
    static class CopilotHealthIndicatorConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "copilotHealthIndicator")
        CopilotHealthIndicator copilotHealthIndicator(CopilotClient client, CopilotHealthProperties properties) {
            return new CopilotHealthIndicator(client, properties.timeout(), properties.startClient(),
                    properties.requireAuthentication());
        }
    }
}
