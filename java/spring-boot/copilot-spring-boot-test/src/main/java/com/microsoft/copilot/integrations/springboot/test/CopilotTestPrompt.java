package com.microsoft.copilot.integrations.springboot.test;

import com.github.copilot.rpc.MessageOptions;

/**
 * A prompt received by the {@link CopilotTestRuntime}.
 *
 * @param agent the agent name, or {@code null} when the call was not made through a Copilot agent
 * @param sessionId the Copilot session that received the prompt
 * @param prompt the prompt text
 * @param options the raw message options sent by the caller
 * @param responseType the requested structured output type, or {@code null} for a text reply
 */
public record CopilotTestPrompt(String agent, String sessionId, String prompt, MessageOptions options,
        Class<?> responseType) {
}
