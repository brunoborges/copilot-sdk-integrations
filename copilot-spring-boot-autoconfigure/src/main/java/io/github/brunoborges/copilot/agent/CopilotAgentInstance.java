package io.github.brunoborges.copilot.agent;

/** Owns a logical conversation binding, not the shared SDK client. */
public interface CopilotAgentInstance<T> extends AutoCloseable {
    T agent();
    String conversationId();

    /** Rejects new invocations and waits for admitted work and cleanup, preserving interrupt status. */
    @Override
    void close();
}
