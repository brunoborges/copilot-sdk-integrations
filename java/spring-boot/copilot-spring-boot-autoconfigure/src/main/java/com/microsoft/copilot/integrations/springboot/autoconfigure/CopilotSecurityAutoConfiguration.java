package com.microsoft.copilot.integrations.springboot.autoconfigure;

import com.github.copilot.CopilotClient;
import com.microsoft.copilot.integrations.springboot.security.OAuth2CopilotTokenResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;

/** Auto-configures per-user Copilot authentication from Spring Security OAuth2 Client. */
@AutoConfiguration(before = CopilotAgentsAutoConfiguration.class)
@ConditionalOnClass({CopilotClient.class, OAuth2AuthorizedClientManager.class})
@ConditionalOnProperty(prefix = "copilot.security.oauth2", name = "registration-id")
@EnableConfigurationProperties(CopilotSecurityProperties.class)
public class CopilotSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OAuth2CopilotTokenResolver.class)
    OAuth2CopilotTokenResolver oauth2CopilotTokenResolver(CopilotSecurityProperties properties,
            ObjectProvider<OAuth2AuthorizedClientManager> managers,
            ObjectProvider<OAuth2AuthorizedClientService> services) {
        var oauth2 = properties.oauth2();
        var manager = managers.getIfUnique();
        if (manager != null) {
            return new OAuth2CopilotTokenResolver(manager, oauth2.registrationId(), oauth2.ambientFallback());
        }
        var service = services.getIfUnique();
        if (service == null) {
            throw new IllegalStateException("copilot.security.oauth2.registration-id requires an "
                    + "OAuth2AuthorizedClientManager or OAuth2AuthorizedClientService bean");
        }
        return new OAuth2CopilotTokenResolver(service, oauth2.registrationId(), oauth2.ambientFallback());
    }
}
