package io.github.brunoborges.copilot.micronaut.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.github.copilot.rpc.SessionMetadata;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotSessionStore;
import io.github.brunoborges.copilot.agent.UserMessage;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.micronaut.CopilotAgentRegistry;
import io.github.brunoborges.copilot.micronaut.CopilotSessionCleanup;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;

class CopilotSessionStoreTest {

    static final String SPEC = "CopilotSessionStoreTest";

    @Requires(property = "spec.name", value = SPEC)
    @CopilotAgent(name = "chat")
    interface Chat {
        CompletableFuture<String> ask(@ConversationId String conversation, @UserMessage String question);
    }

    @Test
    void registersTheStoreAndLeavesCleanupDisabledByDefault() {
        try (var context = ApplicationContext.run(Map.of("spec.name", SPEC))) {
            assertTrue(context.containsBean(CopilotSessionStore.class));
            assertFalse(context.containsBean(CopilotSessionCleanup.class));
        }
    }

    @Test
    void mapsConversationsToTheInvokerSessionIds() {
        try (var context = ApplicationContext.run(Map.of("spec.name", SPEC))) {
            var store = context.getBean(CopilotSessionStore.class);
            var definition = context.getBean(CopilotAgentRegistry.class).factory(Chat.class).definition();

            String sessionId = store.sessionId(Chat.class, "c1");
            assertEquals(CopilotAgentInvoker.sessionId(definition, "c1"), sessionId);
            assertTrue(sessionId.startsWith(CopilotAgentInvoker.MANAGED_SESSION_PREFIX));
            assertThrows(IllegalArgumentException.class, () -> store.sessionId(String.class, "c1"));
        }
    }

    @Test
    void listsAndDeletesAgentSessions() {
        try (var context = ApplicationContext.run(Map.of("spec.name", SPEC))) {
            var store = context.getBean(CopilotSessionStore.class);
            context.getBean(CopilotTestRuntime.class).agent("chat").replies("hi");
            assertEquals("hi", context.getBean(Chat.class).ask("c1", "hello").join());
            String sessionId = store.sessionId(Chat.class, "c1");

            assertEquals(List.of(sessionId), store.list().stream().map(SessionMetadata::getSessionId).toList());
            assertTrue(store.delete(sessionId));
            assertFalse(store.exists(sessionId));
        }
    }

    @Test
    void enablesCleanupWhenAMaxAgeIsSet() {
        try (var context = ApplicationContext.run(Map.of("spec.name", SPEC,
                "micronaut.copilot.sessions.cleanup.max-age", "1h"))) {
            var cleanup = context.getBean(CopilotSessionCleanup.class);
            assertTrue(cleanup.isRunning());
            assertEquals(Duration.ofHours(1), cleanup.cleaner().maxAge());
            assertEquals(Duration.ofMinutes(30), cleanup.interval());
        }
        try (var context = ApplicationContext.run(Map.of("spec.name", SPEC,
                "micronaut.copilot.sessions.cleanup.max-age", "7d",
                "micronaut.copilot.sessions.cleanup.interval", "5m"))) {
            assertEquals(Duration.ofMinutes(5), context.getBean(CopilotSessionCleanup.class).interval());
        }
    }

    @Test
    void rejectsANonPositiveMaxAge() {
        var error = assertThrows(RuntimeException.class, () -> ApplicationContext.run(Map.of("spec.name", SPEC,
                "micronaut.copilot.sessions.cleanup.max-age", "0s")));
        Throwable cause = error;
        while (cause != null && (cause.getMessage() == null || !cause.getMessage().contains("max-age"))) {
            cause = cause.getCause();
        }
        assertTrue(cause != null, () -> "Unexpected failure: " + error);
    }
}
