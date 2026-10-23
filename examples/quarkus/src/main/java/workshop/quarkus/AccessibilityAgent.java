package workshop.quarkus;

import java.util.concurrent.CompletableFuture;
import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "accessibility", instructionsResource = "classpath:/workshop/prompts/accessibility.txt", toolRefs = "accessibilityTools",
        permissionHandlerRef = "accessibilityPermissions",
        availableTools = {"custom:read_latest_accessibility_snapshot", "custom:accessibility_rule_lookup"})
public interface AccessibilityAgent {
    CompletableFuture<String> run(@ConversationId String conversation, @UserMessage String task);
}
