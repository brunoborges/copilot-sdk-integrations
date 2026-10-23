package example.agentwire;

import java.util.concurrent.CompletableFuture;

import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "wire-agent", systemMessageRef = "wireInstructions")
public interface WireAgent {
    CompletableFuture<String> answer(@ConversationId String conversationId, @UserMessage String message);
}
