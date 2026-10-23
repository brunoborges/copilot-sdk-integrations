package io.github.brunoborges.copilot.autoconfigure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

/**
 * @param timeout maximum time a {@code CopilotSessionStore} or cleanup call waits for the Copilot runtime,
 *        including how long a deletion waits for a turn in flight on the session
 * @param cleanup age-based deletion of agent sessions; disabled unless {@code max-age} is set
 */
@ConfigurationProperties("copilot.sessions")
public record CopilotSessionProperties(
        @DefaultValue("2m") Duration timeout,
        @DefaultValue Cleanup cleanup) {

    public CopilotSessionProperties {
        CopilotEndpointProperties.requirePositive(timeout, "copilot.sessions.timeout");
    }

    /**
     * @param maxAge deletes agent sessions not modified for longer than this; unset disables cleanup
     * @param interval time between sweeps; defaults to half of {@code max-age}, between one minute and one hour
     */
    public record Cleanup(Duration maxAge, Duration interval) {

        public Cleanup {
            Assert.isTrue(maxAge == null || maxAge.isPositive(), "copilot.sessions.cleanup.max-age must be positive");
            Assert.isTrue(interval == null || interval.isPositive(),
                    "copilot.sessions.cleanup.interval must be positive");
        }
    }
}
