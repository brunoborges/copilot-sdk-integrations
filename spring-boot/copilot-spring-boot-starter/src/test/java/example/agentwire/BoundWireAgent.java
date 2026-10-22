package example.agentwire;

import java.util.concurrent.CompletableFuture;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(name = "bound-wire", systemMessageRef = "wireInstructions")
public interface BoundWireAgent {
    CompletableFuture<String> answer(@UserMessage String message);
}
