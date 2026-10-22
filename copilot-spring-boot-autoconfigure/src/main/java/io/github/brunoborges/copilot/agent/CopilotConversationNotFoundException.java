package io.github.brunoborges.copilot.agent;

public final class CopilotConversationNotFoundException extends IllegalStateException {
    public CopilotConversationNotFoundException(String agentName) {
        super("Persisted conversation was not found for Copilot agent '" + agentName + "'");
    }
}
