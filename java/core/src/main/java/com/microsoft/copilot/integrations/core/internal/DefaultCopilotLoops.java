package com.microsoft.copilot.integrations.core.internal;

import java.util.function.BiFunction;

import com.microsoft.copilot.integrations.core.CopilotAgents;
import com.microsoft.copilot.integrations.core.CopilotLoop;
import com.microsoft.copilot.integrations.core.CopilotLoops;

public final class DefaultCopilotLoops implements CopilotLoops {

    private final CopilotAgents agents;

    public DefaultCopilotLoops(CopilotAgents agents) {
        Assert.notNull(agents, "CopilotAgents is required");
        this.agents = agents;
    }

    @Override
    public <T> CopilotLoop.Result run(CopilotLoop loop, Class<T> agentType, BiFunction<? super T, String, ?> call) {
        Assert.notNull(loop, "Copilot loop is required");
        return loop.run(step(agentType, call));
    }

    @Override
    public <T> CopilotLoop.Step step(Class<T> agentType, BiFunction<? super T, String, ?> call) {
        Assert.notNull(agentType, "Copilot agent type is required");
        Assert.notNull(call, "Copilot loop call is required");
        return (iteration, prompt) -> {
            try (var instance = agents.create(agentType)) {
                Object result = call.apply(instance.agent(), prompt);
                return result == null ? null : result.toString();
            }
        };
    }
}
