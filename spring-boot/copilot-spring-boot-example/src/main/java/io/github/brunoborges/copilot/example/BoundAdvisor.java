package io.github.brunoborges.copilot.example;

import java.util.concurrent.CompletableFuture;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(name = "bound-advisor", systemMessage = "advisorInstructions",
        permissionHandler = "denyPermissions", tools = "referenceTools", listeners = "conversationOutput")
public interface BoundAdvisor {
    CompletableFuture<String> answer(@UserMessage String question);
}
