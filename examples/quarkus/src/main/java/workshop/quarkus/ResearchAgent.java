package workshop.quarkus;

import java.util.concurrent.CompletableFuture;
import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "research", instructionsResource = "classpath:/workshop/prompts/research.txt", mcpServers = "wikipedia",
        permissionHandlerRef = "researchPermissions",
        availableTools = {"mcp:search", "mcp:readArticle", "mcp:wikipedia-search", "mcp:wikipedia-readArticle"})
public interface ResearchAgent {
    CompletableFuture<String> run(@ConversationId String conversation, @UserMessage String task);
}
