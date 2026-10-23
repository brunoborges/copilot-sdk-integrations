package com.microsoft.copilot.integrations.springboot.bound.callbacks;

import java.util.concurrent.CompletableFuture;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "callback-bound", listenerRefs = "observer")
public interface CallbackAdvisor {
    CompletableFuture<String> answer(@UserMessage String question);
}
