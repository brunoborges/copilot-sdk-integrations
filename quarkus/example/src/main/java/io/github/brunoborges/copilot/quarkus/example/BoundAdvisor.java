package io.github.brunoborges.copilot.quarkus.example;

import java.util.concurrent.CompletableFuture;

import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

/** An agent without a {@code @ConversationId}: obtain bound instances from {@code CopilotAgents}. */
@CopilotAgent(name = "bound-advisor", systemMessageRef = "advisorInstructions",
        permissionHandlerRef = "denyPermissions", toolRefs = "referenceTools", listenerRefs = "conversationOutput")
public interface BoundAdvisor {
    CompletableFuture<String> answer(@UserMessage String question);
}
