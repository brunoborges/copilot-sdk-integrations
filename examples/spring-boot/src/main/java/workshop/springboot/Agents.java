package workshop.springboot;

import java.util.concurrent.CompletableFuture;
import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "museum", instructionsResource = "classpath:/workshop/prompts/museum.txt", toolRefs = "museumTools",
        permissionHandlerRef = "museumPermissions", availableTools = "custom:approved_fact_lookup")
interface MuseumAgent {
    CompletableFuture<String> run(@ConversationId String conversation, @UserMessage String task);
}

@CopilotAgent(name = "accessibility", instructionsResource = "classpath:/workshop/prompts/accessibility.txt", toolRefs = "accessibilityTools",
        permissionHandlerRef = "accessibilityPermissions",
        availableTools = {"custom:read_latest_accessibility_snapshot", "custom:accessibility_rule_lookup"})
interface AccessibilityAgent {
    CompletableFuture<String> run(@ConversationId String conversation, @UserMessage String task);
}

@CopilotAgent(name = "research", instructionsResource = "classpath:/workshop/prompts/research.txt", mcpServers = "wikipedia",
        permissionHandlerRef = "researchPermissions",
        availableTools = {"mcp:search", "mcp:readArticle", "mcp:wikipedia-search", "mcp:wikipedia-readArticle"})
interface ResearchAgent {
    CompletableFuture<String> run(@ConversationId String conversation, @UserMessage String task);
}
