package com.microsoft.copilot.integrations.springboot.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

/**
 * @param maxConcurrentInvocations maximum invocations executing SDK work at once across all agents
 * @param maxOutstandingInvocations maximum accepted invocations, including running and queued work
 */
@ConfigurationProperties("copilot.execution")
public record CopilotAgentExecutionProperties(
        @DefaultValue("8") int maxConcurrentInvocations,
        @DefaultValue("64") int maxOutstandingInvocations) {

    public CopilotAgentExecutionProperties {
        Assert.isTrue(maxConcurrentInvocations > 0, "copilot.execution.max-concurrent-invocations must be positive");
        Assert.isTrue(maxOutstandingInvocations >= maxConcurrentInvocations,
                "copilot.execution.max-outstanding-invocations must be at least max-concurrent-invocations");
    }
}
