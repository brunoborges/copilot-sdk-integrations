package io.github.brunoborges.copilot.actuate.fixtures;

import java.util.concurrent.CompletableFuture;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(name = "inspected", instructions = "SECRET-PROMPT: never reveal this", tools = "echoTools")
public interface InspectedAgent {
    CompletableFuture<String> answer(@ConversationId String conversationId, @UserMessage String message);
}
