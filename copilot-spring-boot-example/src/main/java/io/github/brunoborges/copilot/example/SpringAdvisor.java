package io.github.brunoborges.copilot.example;

import java.util.concurrent.CompletableFuture;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(name = "spring-advisor", systemMessage = "advisorInstructions",
        permissionHandler = "denyPermissions", tools = "referenceTools", listeners = "conversationOutput")
public interface SpringAdvisor {
    CompletableFuture<String> answer(@ConversationId String conversationId, @UserMessage String question);
}
