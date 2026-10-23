package com.microsoft.copilot.integrations.quarkus.example;

import java.util.concurrent.CompletableFuture;

import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

/** A conversational agent: each conversation id maps to its own persisted Copilot session. */
@CopilotAgent(name = "quarkus-advisor", systemMessageRef = "advisorInstructions",
        permissionHandlerRef = "denyPermissions", toolRefs = "referenceTools", listenerRefs = "conversationOutput")
public interface QuarkusAdvisor {
    CompletableFuture<String> answer(@ConversationId String conversationId, @UserMessage String question);
}
