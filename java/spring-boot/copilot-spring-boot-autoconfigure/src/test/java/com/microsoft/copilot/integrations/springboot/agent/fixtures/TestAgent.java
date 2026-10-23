package com.microsoft.copilot.integrations.springboot.agent.fixtures;

import java.util.concurrent.CompletableFuture;

import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "test-agent", instructions = "Be helpful.")
public interface TestAgent {
    CompletableFuture<String> answer(@ConversationId String conversationId, @UserMessage String message);
}
