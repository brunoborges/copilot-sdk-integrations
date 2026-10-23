package io.github.brunoborges.copilot.quarkus.example;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotAgentEvent;
import io.github.brunoborges.copilot.agent.UserMessage;
import io.smallrye.mutiny.Multi;

/** Streams agent events as a Mutiny {@link Multi}. */
@CopilotAgent(name = "streaming-advisor", systemMessageRef = "advisorInstructions",
        permissionHandlerRef = "denyPermissions", toolRefs = "referenceTools", listenerRefs = "conversationOutput")
public interface StreamingAdvisor {
    Multi<CopilotAgentEvent> stream(@ConversationId String conversationId, @UserMessage String question);
}
