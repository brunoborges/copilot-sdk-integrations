package com.microsoft.copilot.integrations.camel;

/**
 * Identity type of agents configured entirely through {@code copilot:agent} endpoint options. Target it with
 * {@code @CopilotAgentHooksFor(CopilotEndpointAgent.class)} to attach hooks to every endpoint-configured agent.
 */
public interface CopilotEndpointAgent {
}
