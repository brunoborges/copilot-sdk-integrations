package com.microsoft.copilot.integrations.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

class InProcessConnectionConflictTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.copilot.connection", "inprocess")
            .overrideConfigKey("quarkus.copilot.cli-url", "localhost:3000")
            .overrideConfigKey("quarkus.copilot.test-runtime.enabled", "false")
            .assertException(error -> {
                Throwable cause = error;
                while (cause != null && (cause.getMessage() == null
                        || !cause.getMessage().contains("quarkus.copilot.connection=inprocess cannot be combined with "
                                + "quarkus.copilot.cli-url"))) {
                    cause = cause.getCause();
                }
                assertTrue(cause != null, () -> "Unexpected failure: " + error);
            });

    @Test
    void failsAtStartup() {
        fail("Startup should have failed");
    }
}
