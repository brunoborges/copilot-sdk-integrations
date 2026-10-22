package io.github.brunoborges.copilot.agent;

/**
 * Thrown when a bound instance requires existing history, for example after
 * {@link CopilotAgents#resume(Class, String)}, but the runtime has no persisted session for it.
 */
public final class CopilotConversationNotFoundException extends IllegalStateException {
    public CopilotConversationNotFoundException(String agentName) {
        super("Persisted conversation was not found for Copilot agent '" + agentName + "'");
    }
}
