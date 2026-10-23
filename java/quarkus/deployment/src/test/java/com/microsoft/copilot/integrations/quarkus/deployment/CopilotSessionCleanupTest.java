package com.microsoft.copilot.integrations.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.microsoft.copilot.integrations.quarkus.runtime.CopilotSessionCleanup;
import io.quarkus.test.QuarkusUnitTest;

class CopilotSessionCleanupTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.copilot.sessions.cleanup.max-age", "1h");

    @Inject
    CopilotSessionCleanup cleanup;

    @Test
    void schedulesSweepsAtHalfTheMaxAge() {
        assertTrue(cleanup.enabled());
        assertTrue(cleanup.isRunning());
        assertEquals(Duration.ofHours(1), cleanup.cleaner().maxAge());
        assertEquals(Duration.ofMinutes(30), cleanup.interval());
        assertEquals(0, cleanup.cleaner().sweep());
    }
}
