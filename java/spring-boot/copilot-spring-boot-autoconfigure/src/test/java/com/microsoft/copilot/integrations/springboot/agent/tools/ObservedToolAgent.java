package com.microsoft.copilot.integrations.springboot.agent.tools;

import java.util.concurrent.CompletableFuture;
import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "observed-tools", toolRefs = "tool")
public interface ObservedToolAgent {
    CompletableFuture<String> answer(@ConversationId String conversationId, @UserMessage String question);
}
