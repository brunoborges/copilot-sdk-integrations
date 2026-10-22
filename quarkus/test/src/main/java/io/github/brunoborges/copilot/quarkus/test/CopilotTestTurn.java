package io.github.brunoborges.copilot.quarkus.test;

import java.util.List;

/**
 * The context of a scripted model turn, passed to {@link CopilotAgentScript#repliesUsing}.
 *
 * @param agent the agent name, or {@code null} when the call was not made through a Copilot agent
 * @param sessionId the Copilot session handling the turn
 * @param prompt the prompt being answered
 * @param toolCalls the tool calls already performed during this turn, in order
 */
public record CopilotTestTurn(String agent, String sessionId, String prompt, List<CopilotTestToolCall> toolCalls) {

    /** The result of the last successful call of the given tool in this turn, or {@code null}. */
    public Object toolResult(String toolName) {
        for (int i = toolCalls.size() - 1; i >= 0; i--) {
            var call = toolCalls.get(i);
            if (call.toolName().equals(toolName) && call.succeeded()) {
                return call.result();
            }
        }
        return null;
    }
}
