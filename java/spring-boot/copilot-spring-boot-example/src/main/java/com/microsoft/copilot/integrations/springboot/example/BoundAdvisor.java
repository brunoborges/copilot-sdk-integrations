package io.github.brunoborges.copilot.example;

import java.util.concurrent.CompletableFuture;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(name = "bound-advisor", systemMessageRef = "advisorInstructions",
        permissionHandlerRef = "denyPermissions", toolRefs = "referenceTools", listenerRefs = "conversationOutput")
public interface BoundAdvisor {
    CompletableFuture<String> answer(@UserMessage String question);
}
