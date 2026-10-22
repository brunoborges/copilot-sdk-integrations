package io.github.brunoborges.copilot.test.app;

import java.util.concurrent.CompletableFuture;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(name = "spring-advisor", model = "gpt-5", instructions = "Answer Spring questions.", toolRefs = "glossary")
public interface SpringAdvisor {

    CompletableFuture<String> ask(@ConversationId String conversationId, @UserMessage String question);

    Transcript stream(@ConversationId String conversationId, @UserMessage String question);
}
