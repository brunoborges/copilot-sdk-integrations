package workshop.micronaut;

import java.util.concurrent.CompletableFuture;
import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "museum", instructionsResource = "classpath:/workshop/prompts/museum.txt", toolRefs = "museumTools",
        permissionHandlerRef = "museumPermissions", availableTools = "custom:approved_fact_lookup")
public interface MuseumAgent {
    CompletableFuture<String> run(@ConversationId String conversation, @UserMessage String task);
}
