package com.microsoft.copilot.integrations.springboot.bound.fixtures;

import java.util.concurrent.CompletableFuture;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "bound-test", instructions = "Be concise.")
public interface BoundAdvisor {
    CompletableFuture<String> answer(@UserMessage String question);
}
