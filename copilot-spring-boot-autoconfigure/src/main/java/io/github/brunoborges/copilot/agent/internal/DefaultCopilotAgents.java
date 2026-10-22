package io.github.brunoborges.copilot.agent.internal;

import java.util.UUID;

import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotAgentInstance;
import io.github.brunoborges.copilot.agent.CopilotAgents;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;

public final class DefaultCopilotAgents implements CopilotAgents {
    private final BeanFactory beans;
    private final Environment environment;

    public DefaultCopilotAgents(BeanFactory beans, Environment environment) {
        this.beans = beans;
        this.environment = environment;
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

    private CopilotAgentFactoryBean factory(Class<?> type) {
        Assert.notNull(type, "Copilot agent type is required");
        Assert.isTrue(type.isInterface() && type.isAnnotationPresent(CopilotAgent.class),
                "Copilot agent type must be a directly annotated interface");
        String name = CopilotAgentDefinition.name(type, environment);
        Assert.isTrue(beans.containsBean("&" + name), "Copilot agent definition was not discovered: " + type.getName());
        var factory = beans.getBean("&" + name, CopilotAgentFactoryBean.class);
        Assert.isTrue(factory.agentType() == type, "A different Copilot agent owns bean name " + name);
        Assert.isTrue(factory.boundDefinition(),
                "Bound instances require an ID-less interface with one @UserMessage parameter; inject explicit-ID agents directly");
        return factory;
    }
}
