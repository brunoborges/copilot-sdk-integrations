package io.github.brunoborges.copilot.micronaut.example;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotAgentEvent;
import io.github.brunoborges.copilot.agent.UserMessage;
import reactor.core.publisher.Flux;

/** Streams agent events as a Reactor {@link Flux}. */
@CopilotAgent(name = "streaming-advisor", systemMessageRef = "advisorInstructions",
        permissionHandlerRef = "denyPermissions", toolRefs = "referenceTools", listenerRefs = "conversationOutput")
public interface StreamingAdvisor {
    Flux<CopilotAgentEvent> stream(@ConversationId String conversationId, @UserMessage String question);
}
