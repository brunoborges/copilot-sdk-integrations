package io.github.brunoborges.copilot.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;
import io.quarkus.test.QuarkusUnitTest;

class MissingToolRefTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClasses(Misconfigured.class))
            .assertException(error -> {
                Throwable cause = error;
                while (cause != null && (cause.getMessage() == null || !cause.getMessage().contains("missingTools"))) {
                    cause = cause.getCause();
                }
                assertTrue(cause != null, () -> "Unexpected failure: " + error);
            });

    @CopilotAgent(name = "misconfigured", toolRefs = "missingTools")
    interface Misconfigured {
        CompletableFuture<String> ask(@ConversationId String id, @UserMessage String question);
    }

    @Test
    void failsAtStartup() {
        fail("Startup should have failed");
    }
}
