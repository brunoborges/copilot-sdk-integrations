package com.microsoft.copilot.integrations.springboot.autoconfigure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

/**
 * @param timeout maximum time a {@code copilot} endpoint operation waits for the Copilot runtime
 * @param startClient whether the endpoint may start a lazily configured client that is not yet connected
 * @param showInstructions whether agent system instructions are included in the {@code agents} view
 * @param includeAllSessions whether the {@code sessions} view lists sessions not created by this starter
 * @param showSessionSummaries whether the {@code sessions} view includes runtime-generated session summaries,
 *        which may contain prompt or business content
 */
@ConfigurationProperties("copilot.endpoint")
public record CopilotEndpointProperties(
        @DefaultValue("5s") Duration timeout,
        @DefaultValue("false") boolean startClient,
        @DefaultValue("false") boolean showInstructions,
        @DefaultValue("false") boolean includeAllSessions,
        @DefaultValue("false") boolean showSessionSummaries) {

    public CopilotEndpointProperties {
        requirePositive(timeout, "copilot.endpoint.timeout");
    }

    static void requirePositive(Duration timeout, String name) {
        Assert.notNull(timeout, name + " is required");
        Assert.isTrue(timeout.compareTo(Duration.ZERO) > 0 && timeout.compareTo(Duration.ofNanos(Long.MAX_VALUE)) <= 0,
                name + " must be positive and representable in nanoseconds");
    }
}
