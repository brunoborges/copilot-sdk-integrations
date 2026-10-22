package io.github.brunoborges.copilot.autoconfigure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param timeout maximum time a health check waits for the Copilot runtime
 * @param startClient whether a health check may start a lazily configured client that is not yet connected
 * @param requireAuthentication whether an unauthenticated runtime reports {@code DOWN}
 */
@ConfigurationProperties("copilot.health")
public record CopilotHealthProperties(
        @DefaultValue("5s") Duration timeout,
        @DefaultValue("false") boolean startClient,
        @DefaultValue("true") boolean requireAuthentication) {

    public CopilotHealthProperties {
        CopilotEndpointProperties.requirePositive(timeout, "copilot.health.timeout");
    }
}
