package io.github.brunoborges.copilot.example;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotAgentEvent;
import io.github.brunoborges.copilot.agent.UserMessage;
import reactor.core.publisher.Flux;

@CopilotAgent(name = "streaming-advisor", systemMessage = "advisorInstructions",
        permissionHandler = "denyPermissions", tools = "referenceTools", listeners = "conversationOutput")
public interface StreamingAdvisor {
    Flux<CopilotAgentEvent> stream(@ConversationId String conversationId, @UserMessage String question);
}
