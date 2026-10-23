package com.microsoft.copilot.integrations.quarkus.example;

import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.CopilotAgentEvent;
import com.microsoft.copilot.integrations.core.UserMessage;
import io.smallrye.mutiny.Multi;

/** Streams agent events as a Mutiny {@link Multi}. */
@CopilotAgent(name = "streaming-advisor", systemMessageRef = "advisorInstructions",
        permissionHandlerRef = "denyPermissions", toolRefs = "referenceTools", listenerRefs = "conversationOutput")
public interface StreamingAdvisor {
    Multi<CopilotAgentEvent> stream(@ConversationId String conversationId, @UserMessage String question);
}
