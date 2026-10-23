package io.github.brunoborges.copilot.camel;

/**
 * Identity type of agents configured entirely through {@code copilot:agent} endpoint options. Target it with
 * {@code @CopilotAgentHooksFor(CopilotEndpointAgent.class)} to attach hooks to every endpoint-configured agent.
 */
public interface CopilotEndpointAgent {
}
