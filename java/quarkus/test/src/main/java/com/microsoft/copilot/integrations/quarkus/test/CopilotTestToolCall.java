package com.microsoft.copilot.integrations.quarkus.test;

import java.util.Map;

/**
 * A tool invocation performed by the {@link CopilotTestRuntime} on behalf of a scripted model turn.
 *
 * @param agent the agent name, or {@code null} when the call was not made through a Copilot agent
 * @param sessionId the Copilot session in which the tool ran
 * @param toolCallId the generated tool call ID
 * @param toolName the invoked tool name
 * @param arguments the arguments passed to the tool
 * @param result the value returned by the tool handler, or {@code null} when it failed
 * @param failure the failure raised by the tool handler, or {@code null} when it succeeded
 */
public record CopilotTestToolCall(String agent, String sessionId, String toolCallId, String toolName,
        Map<String, Object> arguments, Object result, Throwable failure) {

    /** Whether the tool handler completed without throwing. */
    public boolean succeeded() {
        return failure == null;
    }
}
