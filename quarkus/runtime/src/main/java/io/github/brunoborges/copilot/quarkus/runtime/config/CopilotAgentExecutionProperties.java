package io.github.brunoborges.copilot.quarkus.runtime.config;

import io.github.brunoborges.copilot.agent.internal.Assert;

/**
 * @param maxConcurrentInvocations maximum invocations executing SDK work at once across all agents
 * @param maxOutstandingInvocations maximum accepted invocations, including running and queued work
 */
public record CopilotAgentExecutionProperties(
        int maxConcurrentInvocations,
        int maxOutstandingInvocations) {

    public CopilotAgentExecutionProperties {
        Assert.isTrue(maxConcurrentInvocations > 0, "quarkus.copilot.execution.max-concurrent-invocations must be positive");
        Assert.isTrue(maxOutstandingInvocations >= maxConcurrentInvocations,
                "quarkus.copilot.execution.max-outstanding-invocations must be at least max-concurrent-invocations");
    }
}
