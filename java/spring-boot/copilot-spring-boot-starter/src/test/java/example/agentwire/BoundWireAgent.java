package example.agentwire;

import java.util.concurrent.CompletableFuture;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "bound-wire", systemMessageRef = "wireInstructions")
public interface BoundWireAgent {
    CompletableFuture<String> answer(@UserMessage String message);
}
