package io.github.brunoborges.copilot.autoconfigure;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;
import io.github.brunoborges.copilot.actuate.CopilotInfoContributor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.info.ConditionalOnEnabledInfoContributor;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration(after = CopilotAutoConfiguration.class)
@ConditionalOnClass({CopilotClient.class, InfoContributor.class, ConditionalOnEnabledInfoContributor.class})
@ConditionalOnBean(CopilotClient.class)
@ConditionalOnProperty(prefix = "copilot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CopilotInfoContributorAutoConfiguration {

    // Nested so the actuator-only condition is parsed only when actuator auto-configuration is present.
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEnabledInfoContributor("copilot")
    static class CopilotInfoContributorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        CopilotInfoContributor copilotInfoContributor(ObjectProvider<CopilotClientOptions> options) {
            return new CopilotInfoContributor(CopilotInfoContributor.connectionMode(options.getIfAvailable()));
        }
    }
}
