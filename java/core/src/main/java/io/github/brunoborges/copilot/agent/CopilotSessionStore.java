package io.github.brunoborges.copilot.agent;

import java.util.List;
import java.util.Optional;

import com.github.copilot.rpc.SessionMetadata;

/**
 * Lists, reads, and deletes the persisted Copilot sessions behind agent conversations. Inject it to build a
 * conversation list, restore a transcript, or let users delete their history.
 *
 * <p>Every method blocks until the runtime responds and starts a lazily configured client on first use. Agents
 * persist each conversation in a session whose ID is derived from the agent and conversation ID; use
 * {@link #sessionId(Class, String)} to find it.
 */
public interface CopilotSessionStore {

    /**
     * Lists local sessions created by agents (IDs starting with {@code agent-}), most recently modified first.
     * Sessions created outside this library on a shared runtime are not listed.
     */
    List<SessionMetadata> list();

    /** Returns a session's metadata, or empty when the session does not exist. */
    Optional<SessionMetadata> get(String sessionId);

    /** Returns whether the session exists. */
    default boolean exists(String sessionId) {
        return get(sessionId).isPresent();
    }

    /**
     * Reads a session's user and assistant messages, in order, without resuming it. Sub-agent messages, tool calls,
     * and empty assistant messages are omitted.
     *
     * @return the messages, or empty when the session does not exist
     */
    Optional<List<CopilotSessionMessage>> messages(String sessionId);

    /**
     * Deletes a session and its history. Waits for a turn in flight on the session to finish first, and holds
     * turns submitted meanwhile until the deletion completes.
     *
     * @return {@code true} if the session was deleted, {@code false} if it did not exist
     */
    boolean delete(String sessionId);

    /**
     * Returns the session ID that an invocation of {@code agentType} on the current thread uses for
     * {@code conversationId}, including the {@linkplain CopilotTokenResolver#partitionKey(String, String) caller's
     * partition} when the agent has a token resolver.
     *
     * @throws IllegalArgumentException if {@code agentType} is not a discovered agent
     */
    String sessionId(Class<?> agentType, String conversationId);
}
