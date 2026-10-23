package io.github.brunoborges.copilot.micronaut;

import java.util.Objects;

/**
 * Registers one {@code @CopilotAgent} interface. Instances are contributed by the factories that
 * {@code copilot-micronaut-processor} generates at compile time; applications do not declare them.
 */
public final class CopilotAgentRegistration {

    private final Class<?> agentType;

    public CopilotAgentRegistration(Class<?> agentType) {
        this.agentType = Objects.requireNonNull(agentType, "agentType");
    }

    public Class<?> agentType() {
        return agentType;
    }

    @Override
    public String toString() {
        return "CopilotAgentRegistration[" + agentType.getName() + "]";
    }
}
