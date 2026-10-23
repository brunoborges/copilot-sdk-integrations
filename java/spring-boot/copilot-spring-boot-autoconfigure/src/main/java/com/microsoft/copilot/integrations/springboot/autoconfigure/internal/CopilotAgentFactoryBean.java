package com.microsoft.copilot.integrations.springboot.autoconfigure.internal;

import com.microsoft.copilot.integrations.core.CopilotAgentInstance;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentDefinition;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentFactory;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentInvoker;
import com.microsoft.copilot.integrations.springboot.autoconfigure.CopilotAgentsProperties;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

/**
 * Registers one {@code @CopilotAgent} interface as a Spring bean. Agents with {@code @ConversationId} methods expose
 * their JDK proxy; ID-less agents expose their {@link CopilotAgentDefinition}, bound per conversation through
 * {@link com.microsoft.copilot.integrations.core.CopilotAgents}.
 */
public final class CopilotAgentFactoryBean implements FactoryBean<Object>, InitializingBean {

    private final Class<?> type;
    private final CopilotAgentInvoker invoker;
    private final BeanFactory beans;
    private final Environment environment;
    private final ResourceLoader resources;
    private final CopilotAgentsProperties properties;
    private final boolean bound;
    private CopilotAgentFactory factory;

    public CopilotAgentFactoryBean(Class<?> type, CopilotAgentInvoker invoker, BeanFactory beans,
            Environment environment, ResourceLoader resources, CopilotAgentsProperties properties) {
        this.type = type;
        this.invoker = invoker;
        this.beans = beans;
        this.environment = environment;
        this.resources = resources;
        this.properties = properties;
        bound = requiresBinding(type);
    }

    @Override
    public void afterPropertiesSet() {
        factory = new CopilotAgentFactory(type, invoker,
                SpringAgentBinding.context(beans, environment, resources, properties));
    }

    public static boolean requiresBinding(Class<?> type) {
        return CopilotAgentFactory.requiresBinding(type);
    }

    public Class<?> agentType() {
        return type;
    }

    public boolean boundDefinition() {
        return bound;
    }

    /** Returns the resolved definition, or {@code null} before initialization. */
    public CopilotAgentDefinition definition() {
        return factory == null ? null : factory.definition();
    }

    /** Returns the shared agent factory, or {@code null} before initialization. */
    public CopilotAgentFactory factory() {
        return factory;
    }

    public <T> CopilotAgentInstance<T> bind(Class<T> requestedType, String conversationId, boolean resume) {
        return factory.bind(requestedType, conversationId, resume);
    }

    @Override
    public Object getObject() {
        return bound ? factory.definition() : factory.getObject();
    }

    @Override
    public Class<?> getObjectType() {
        return bound ? CopilotAgentDefinition.class : type;
    }
}
