package io.github.brunoborges.copilot.test.app;

import java.util.concurrent.CompletableFuture;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(name = "code-reviewer")
public interface CodeReviewer {

    CompletableFuture<String> review(@ConversationId String conversationId, @UserMessage String code);
}
