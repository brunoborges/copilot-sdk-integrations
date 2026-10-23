package com.microsoft.copilot.integrations.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.copilot.CopilotClient;

import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusUnitTest;

class DisabledExtensionTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClasses(Ignored.class))
            .overrideConfigKey("quarkus.copilot.enabled", "false")
            .overrideConfigKey("quarkus.copilot.test-runtime.enabled", "false");

    @CopilotAgent(name = "ignored")
    interface Ignored {
        CompletableFuture<String> ask(@ConversationId String id, @UserMessage String question);
    }

    @Test
    void registersNothing() {
        assertFalse(Arc.container().instance(CopilotClient.class).isAvailable());
        assertFalse(Arc.container().instance(Ignored.class).isAvailable());
    }
}
