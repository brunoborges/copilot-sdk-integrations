package com.microsoft.copilot.integrations.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.copilot.rpc.SessionMetadata;

import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.CopilotSessionStore;
import com.microsoft.copilot.integrations.core.UserMessage;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentInvoker;
import com.microsoft.copilot.integrations.quarkus.runtime.CopilotAgentRegistry;
import com.microsoft.copilot.integrations.quarkus.runtime.CopilotSessionCleanup;
import com.microsoft.copilot.integrations.quarkus.test.CopilotTestRuntime;
import io.quarkus.test.QuarkusUnitTest;

class CopilotSessionStoreTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClasses(Chat.class));

    @CopilotAgent(name = "chat")
    interface Chat {
        CompletableFuture<String> ask(@ConversationId String conversation, @UserMessage String question);
    }

    @Inject
    Chat chat;

    @Inject
    CopilotSessionStore store;

    @Inject
    CopilotSessionCleanup cleanup;

    @Inject
    CopilotAgentRegistry registry;

    @Inject
    CopilotTestRuntime copilot;

    @AfterEach
    void reset() {
        copilot.reset();
    }

    @Test
    void leavesCleanupDisabledByDefault() {
        assertFalse(cleanup.enabled());
        assertFalse(cleanup.isRunning());
        assertNull(cleanup.interval());
    }

    @Test
    void mapsConversationsToTheInvokerSessionIds() {
        var definition = registry.factory(Chat.class).definition();

        String sessionId = store.sessionId(Chat.class, "c1");
        assertEquals(CopilotAgentInvoker.sessionId(definition, "c1"), sessionId);
        assertTrue(sessionId.startsWith(CopilotAgentInvoker.MANAGED_SESSION_PREFIX));
    }

    @Test
    void listsAndDeletesAgentSessions() {
        copilot.agent("chat").replies("hi");
        assertEquals("hi", chat.ask("c1", "hello").join());
        String sessionId = store.sessionId(Chat.class, "c1");

        List<String> ids = store.list().stream().map(SessionMetadata::getSessionId).toList();
        assertEquals(List.of(sessionId), ids);
        assertTrue(store.exists(sessionId));
        assertTrue(store.delete(sessionId));
        assertFalse(store.exists(sessionId));
        assertFalse(store.delete(sessionId));
    }
}
