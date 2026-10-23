package com.microsoft.copilot.integrations.springboot.test.app;

import java.util.concurrent.CompletableFuture;

import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "code-reviewer")
public interface CodeReviewer {

    CompletableFuture<String> review(@ConversationId String conversationId, @UserMessage String code);
}
