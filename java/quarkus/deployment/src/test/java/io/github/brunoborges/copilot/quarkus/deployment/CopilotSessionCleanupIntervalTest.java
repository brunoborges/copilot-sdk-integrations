package io.github.brunoborges.copilot.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.github.brunoborges.copilot.quarkus.runtime.CopilotSessionCleanup;
import io.quarkus.test.QuarkusUnitTest;

class CopilotSessionCleanupIntervalTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.copilot.sessions.cleanup.max-age", "7d")
            .overrideConfigKey("quarkus.copilot.sessions.cleanup.interval", "5m");

    @Inject
    CopilotSessionCleanup cleanup;

    @Test
    void usesTheConfiguredInterval() {
        assertEquals(Duration.ofDays(7), cleanup.cleaner().maxAge());
        assertEquals(Duration.ofMinutes(5), cleanup.interval());
    }
}
