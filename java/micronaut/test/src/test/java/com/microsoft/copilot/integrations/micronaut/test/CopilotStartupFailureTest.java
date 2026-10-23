package io.github.brunoborges.copilot.micronaut.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.github.copilot.CopilotClient;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;
import io.github.brunoborges.copilot.micronaut.CopilotAgentRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;

class CopilotStartupFailureTest {

    static final String SPEC = "CopilotStartupFailureTest";

    @Requires(property = "spec.name", value = SPEC)
    @CopilotAgent(name = "misconfigured", toolRefs = "missingTools")
    interface Misconfigured {
        CompletableFuture<String> ask(@ConversationId String id, @UserMessage String question);
    }

    @Test
    void failsStartupOnAMissingToolRef() {
        var error = assertThrows(RuntimeException.class, () -> ApplicationContext.run(Map.of("spec.name", SPEC)));

        Throwable cause = error;
        while (cause != null && (cause.getMessage() == null || !cause.getMessage().contains("missingTools"))) {
            cause = cause.getCause();
        }
        assertTrue(cause != null, () -> "Unexpected failure: " + error);
    }

    @Test
    void registersNothingWhenDisabled() {
        try (var context = ApplicationContext.run(Map.of("spec.name", SPEC, "micronaut.copilot.enabled", "false",
                "micronaut.copilot.test-runtime.enabled", "false"))) {
            assertFalse(context.containsBean(CopilotClient.class));
            assertFalse(context.containsBean(CopilotAgentRegistry.class));
            assertFalse(context.containsBean(Misconfigured.class));
        }
    }
}
