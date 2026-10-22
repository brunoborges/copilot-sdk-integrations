package example.agentwire;

import java.util.concurrent.CompletableFuture;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(name = "wire-agent", systemMessageRef = "wireInstructions")
public interface WireAgent {
    CompletableFuture<String> answer(@ConversationId String conversationId, @UserMessage String message);
}
