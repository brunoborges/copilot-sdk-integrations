package com.microsoft.copilot.integrations.springboot.test.app;

import java.util.concurrent.CompletableFuture;

import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "spring-advisor", model = "gpt-5", instructions = "Answer Spring questions.", toolRefs = "glossary")
public interface SpringAdvisor {

    CompletableFuture<String> ask(@ConversationId String conversationId, @UserMessage String question);

    Transcript stream(@ConversationId String conversationId, @UserMessage String question);
}
