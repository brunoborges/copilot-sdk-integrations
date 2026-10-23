package com.microsoft.copilot.integrations.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.microsoft.copilot.integrations.core.CopilotAgent;
import io.quarkus.test.QuarkusUnitTest;

class NonInterfaceAgentTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClasses(NotAnInterface.class))
            .assertException(error -> {
                Throwable cause = error;
                while (cause != null && (cause.getMessage() == null
                        || !cause.getMessage().contains("@CopilotAgent must annotate an interface"))) {
                    cause = cause.getCause();
                }
                assertTrue(cause != null, () -> "Unexpected failure: " + error);
            });

    @CopilotAgent(name = "broken")
    static class NotAnInterface {
    }

    @Test
    void failsTheBuild() {
        fail("The build should have failed");
    }
}
