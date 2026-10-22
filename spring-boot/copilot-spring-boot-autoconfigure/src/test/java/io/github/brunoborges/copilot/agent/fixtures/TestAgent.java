package io.github.brunoborges.copilot.agent.fixtures;

import java.util.concurrent.CompletableFuture;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(name = "test-agent", instructions = "Be helpful.")
public interface TestAgent {
    CompletableFuture<String> answer(@ConversationId String conversationId, @UserMessage String message);
}
