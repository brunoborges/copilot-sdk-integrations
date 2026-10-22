package io.github.brunoborges.copilot.agent.internal;


/**
 * @param maxConcurrentInvocations maximum invocations executing SDK work at once across all agents
 * @param maxOutstandingInvocations maximum accepted invocations, including running and queued work
 */
public record CopilotAgentExecutionLimits(
        int maxConcurrentInvocations,
        int maxOutstandingInvocations) {

    /** Limits used when no configuration is supplied. */
    public static final CopilotAgentExecutionLimits DEFAULT = new CopilotAgentExecutionLimits(8, 64);

    public CopilotAgentExecutionLimits {
        Assert.isTrue(maxConcurrentInvocations > 0, "max-concurrent-invocations must be positive");
        Assert.isTrue(maxOutstandingInvocations >= maxConcurrentInvocations,
                "max-outstanding-invocations must be at least max-concurrent-invocations");
    }
}
