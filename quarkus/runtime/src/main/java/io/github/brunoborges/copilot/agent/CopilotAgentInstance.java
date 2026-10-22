package io.github.brunoborges.copilot.agent;

/**
 * Owns a logical conversation binding, not the shared SDK client. Closing neither closes the client nor
 * deletes persisted history.
 *
 * @param <T> the ID-less agent interface
 */
public interface CopilotAgentInstance<T> extends AutoCloseable {

    /** Returns the bound agent proxy; fails once the instance is closed. */
    T agent();

    /** Returns the conversation key; persist it to {@link CopilotAgents#resume(Class, String) resume} later. */
    String conversationId();

    /** Rejects new invocations and waits for admitted work and cleanup, preserving interrupt status. */
    @Override
    void close();
}
