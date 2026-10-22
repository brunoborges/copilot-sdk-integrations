package io.github.brunoborges.copilot.actuate.fixtures;

import java.util.concurrent.CompletableFuture;

import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(name = "bound-inspected", model = "claude-sonnet-4.5")
public interface BoundInspectedAgent {
    CompletableFuture<String> answer(@UserMessage String message);
}
