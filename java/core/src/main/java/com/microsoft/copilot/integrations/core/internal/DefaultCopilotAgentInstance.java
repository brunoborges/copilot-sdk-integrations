package com.microsoft.copilot.integrations.core.internal;

import com.microsoft.copilot.integrations.core.CopilotAgentInstance;

public final class DefaultCopilotAgentInstance<T> implements CopilotAgentInstance<T> {
    private final T agent;
    private final String conversationId;
    private final CopilotAgentLifetime lifetime;

    public DefaultCopilotAgentInstance(T agent, String conversationId, CopilotAgentLifetime lifetime) {
        this.agent = agent;
        this.conversationId = conversationId;
        this.lifetime = lifetime;
    }

    @Override
    public T agent() {
        lifetime.assertOpen();
        return agent;
    }

    @Override
    public String conversationId() {
        return conversationId;
    }

    @Override
    public void close() {
        lifetime.close();
    }
}
