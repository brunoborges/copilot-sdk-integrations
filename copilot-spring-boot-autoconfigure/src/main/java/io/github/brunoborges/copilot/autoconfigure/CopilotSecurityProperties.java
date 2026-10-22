package io.github.brunoborges.copilot.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param oauth2 per-user token resolution from Spring Security OAuth2 Client
 */
@ConfigurationProperties("copilot.security")
public record CopilotSecurityProperties(@NestedConfigurationProperty @DefaultValue OAuth2 oauth2) {

    /**
     * @param registrationId OAuth2 client registration whose access token authenticates each user's Copilot
     *        sessions; when set, an {@code OAuth2CopilotTokenResolver} is auto-configured
     * @param ambientFallback whether invocations without an authenticated user or authorized client use the
     *        client's ambient credentials instead of failing
     */
    public record OAuth2(String registrationId, @DefaultValue("false") boolean ambientFallback) {
    }
}
