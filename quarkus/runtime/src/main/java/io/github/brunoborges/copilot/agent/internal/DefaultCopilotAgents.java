package io.github.brunoborges.copilot.agent.internal;

import java.util.UUID;
import java.util.function.Function;

import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotAgentInstance;
import io.github.brunoborges.copilot.agent.CopilotAgents;

public final class DefaultCopilotAgents implements CopilotAgents {

    private final Function<Class<?>, CopilotAgentFactory> factories;

    /** @param factories returns the discovered factory of an agent type, or {@code null} when not discovered */
    public DefaultCopilotAgents(Function<Class<?>, CopilotAgentFactory> factories) {
        this.factories = factories;
    }

    @Override
    public <T> CopilotAgentInstance<T> create(Class<T> agentType) {
        return factory(agentType).bind(agentType, UUID.randomUUID().toString(), false);
    }

    @Override
    public <T> CopilotAgentInstance<T> resume(Class<T> agentType, String conversationId) {
        Assert.hasText(conversationId, "Conversation ID to resume must not be blank");
        return factory(agentType).bind(agentType, conversationId, true);
    }

    private CopilotAgentFactory factory(Class<?> type) {
        Assert.notNull(type, "Copilot agent type is required");
        Assert.isTrue(type.isInterface() && type.isAnnotationPresent(CopilotAgent.class),
                "Copilot agent type must be a directly annotated interface");
        var factory = factories.apply(type);
        Assert.notNull(factory, "Copilot agent definition was not discovered: " + type.getName());
        Assert.isTrue(factory.agentType() == type, "A different Copilot agent is registered for " + type.getName());
        Assert.isTrue(factory.boundDefinition(),
                "Bound instances require an ID-less interface without @ConversationId parameters; inject explicit-ID agents directly");
        return factory;
    }
}
