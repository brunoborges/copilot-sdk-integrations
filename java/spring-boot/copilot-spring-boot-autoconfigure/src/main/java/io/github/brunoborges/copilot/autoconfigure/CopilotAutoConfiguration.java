package io.github.brunoborges.copilot.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;

@AutoConfiguration
@ConditionalOnClass(CopilotClient.class)
@ConditionalOnMissingBean(CopilotClient.class)
@ConditionalOnProperty(prefix = "copilot", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CopilotProperties.class)
public class CopilotAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CopilotClientOptions copilotClientOptions(CopilotProperties properties,
            ObjectProvider<CopilotConnectionDetails> connectionDetails) {
        var details = connectionDetails.getIfAvailable();
        return details == null ? properties.toOptions() : properties.toOptions(details);
    }

    @Bean(destroyMethod = "close")
    @Lazy(false)
    CopilotClient copilotClient(CopilotClientOptions options, CopilotProperties properties) {
        var client = new CopilotClient(options);
        if (properties.eagerStart()) {
            CopilotClientLifecycle.start(client, properties.startupTimeout());
        }
        return client;
    }
}
