package com.microsoft.copilot.integrations.micronaut.example;

import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.CopilotAgentEvent;
import com.microsoft.copilot.integrations.core.UserMessage;
import reactor.core.publisher.Flux;

/** Streams agent events as a Reactor {@link Flux}. */
@CopilotAgent(name = "streaming-advisor", systemMessageRef = "advisorInstructions",
        permissionHandlerRef = "denyPermissions", toolRefs = "referenceTools", listenerRefs = "conversationOutput")
public interface StreamingAdvisor {
    Flux<CopilotAgentEvent> stream(@ConversationId String conversationId, @UserMessage String question);
}
