package com.microsoft.copilot.integrations.micronaut.example;

import java.util.concurrent.CompletableFuture;

import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

/** An agent without a {@code @ConversationId}: obtain bound instances from {@code CopilotAgents}. */
@CopilotAgent(name = "bound-advisor", systemMessageRef = "advisorInstructions",
        permissionHandlerRef = "denyPermissions", toolRefs = "referenceTools", listenerRefs = "conversationOutput")
public interface BoundAdvisor {
    CompletableFuture<String> answer(@UserMessage String question);
}
