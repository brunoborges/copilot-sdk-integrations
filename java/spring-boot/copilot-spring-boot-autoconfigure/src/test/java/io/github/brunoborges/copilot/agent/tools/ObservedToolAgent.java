package io.github.brunoborges.copilot.agent.tools;

import java.util.concurrent.CompletableFuture;
import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(name = "observed-tools", toolRefs = "tool")
public interface ObservedToolAgent {
    CompletableFuture<String> answer(@ConversationId String conversationId, @UserMessage String question);
}
