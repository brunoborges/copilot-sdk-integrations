package io.github.brunoborges.copilot.agent.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.github.copilot.ConnectionState;
import com.github.copilot.CopilotClient;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.rpc.EventsCursorStatus;
import com.github.copilot.generated.rpc.SessionsReadPersistedEventsParams;
import com.github.copilot.rpc.SessionMetadata;

/**
 * Session persistence operations on the shared client. A seam so the session store and cleanup can be tested
 * without a runtime.
 */
public interface CopilotSessionStorage {

    /** Whether the client is connected; cleanup never starts a client on its own. */
    boolean isStarted();

    CompletableFuture<List<SessionMetadata>> list();

    /** Completes with {@code null} when the session does not exist. */
    CompletableFuture<SessionMetadata> get(String sessionId);

    /** Reads every persisted event of an existing session, without resuming it. */
    CompletableFuture<List<SessionEvent>> readEvents(String sessionId);

    CompletableFuture<Void> delete(String sessionId);

    static CopilotSessionStorage of(CopilotClient client) {
        Assert.notNull(client, "CopilotClient is required");
        return of(() -> client);
    }

    /** @param client supplies the current client, or {@code null} while none is available */
    static CopilotSessionStorage of(Supplier<CopilotClient> client) {
        Assert.notNull(client, "CopilotClient supplier is required");
        return new ClientStorage(client);
    }

    final class ClientStorage implements CopilotSessionStorage {

        private static final long PAGE_SIZE = 1000;

        private final Supplier<CopilotClient> client;

        private ClientStorage(Supplier<CopilotClient> client) {
            this.client = client;
        }

        @Override
        public boolean isStarted() {
            var current = client.get();
            return current != null && current.getState() == ConnectionState.CONNECTED;
        }

        @Override
        public CompletableFuture<List<SessionMetadata>> list() {
            return client().listSessions();
        }

        @Override
        public CompletableFuture<SessionMetadata> get(String sessionId) {
            return client().getSessionMetadata(sessionId);
        }

        @Override
        public CompletableFuture<List<SessionEvent>> readEvents(String sessionId) {
            return page(client(), sessionId, null, new ArrayList<>());
        }

        @Override
        public CompletableFuture<Void> delete(String sessionId) {
            return client().deleteSession(sessionId);
        }

        private static CompletableFuture<List<SessionEvent>> page(CopilotClient client, String sessionId, String cursor,
                List<SessionEvent> events) {
            return client.getRpc().sessions
                    .readPersistedEvents(new SessionsReadPersistedEventsParams(sessionId, cursor, PAGE_SIZE, null))
                    .thenCompose(page -> {
                        if (page.cursorStatus() == EventsCursorStatus.EXPIRED) {
                            throw new IllegalStateException(
                                    "Reading the history of Copilot session '" + sessionId + "' expired before it completed");
                        }
                        if (page.events() != null) {
                            events.addAll(page.events());
                        }
                        return Boolean.TRUE.equals(page.hasMore()) && page.cursor() != null
                                ? page(client, sessionId, page.cursor(), events)
                                : CompletableFuture.completedFuture(events);
                    });
        }

        private CopilotClient client() {
            var current = client.get();
            if (current == null) {
                throw new IllegalStateException("Copilot client is not available");
            }
            return current;
        }
    }
}
