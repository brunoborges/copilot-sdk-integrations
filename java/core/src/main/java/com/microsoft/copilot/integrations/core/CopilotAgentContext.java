package com.microsoft.copilot.integrations.core;

/**
 * Identifies the agent invocation that produced an event.
 *
 * @param agentName the {@link CopilotAgent#name()} of the invoked agent
 * @param conversationId the application conversation key, either supplied or generated for a bound instance
 * @param sessionId the SDK session ID derived from the agent type, name, and conversation key
 */
public record CopilotAgentContext(String agentName, String conversationId, String sessionId) {
}
