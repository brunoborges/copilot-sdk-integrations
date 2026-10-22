package io.github.brunoborges.copilot.bound.fixtures;

import java.util.concurrent.CompletableFuture;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(name = "bound-test", instructions = "Be concise.")
public interface BoundAdvisor {
    CompletableFuture<String> answer(@UserMessage String question);
}
