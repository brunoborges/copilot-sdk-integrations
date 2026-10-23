package com.microsoft.copilot.integrations.springboot.actuate.fixtures;

import java.util.concurrent.CompletableFuture;

import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "bound-inspected", model = "claude-sonnet-4.5")
public interface BoundInspectedAgent {
    CompletableFuture<String> answer(@UserMessage String message);
}
