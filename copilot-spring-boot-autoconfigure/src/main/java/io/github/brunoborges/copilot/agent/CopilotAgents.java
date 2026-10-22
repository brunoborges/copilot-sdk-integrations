package io.github.brunoborges.copilot.agent;

/** Creates conversation-bound instances of discovered ID-less agent definitions. */
public interface CopilotAgents {
    <T> CopilotAgentInstance<T> create(Class<T> agentType);

    /** Binds an existing key; history existence is checked strictly when the instance is invoked. */
    <T> CopilotAgentInstance<T> resume(Class<T> agentType, String conversationId);
}
