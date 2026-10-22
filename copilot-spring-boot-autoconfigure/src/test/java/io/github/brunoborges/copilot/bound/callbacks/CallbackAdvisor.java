package io.github.brunoborges.copilot.bound.callbacks;

import java.util.concurrent.CompletableFuture;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(name = "callback-bound", listeners = "observer")
public interface CallbackAdvisor {
    CompletableFuture<String> answer(@UserMessage String question);
}
