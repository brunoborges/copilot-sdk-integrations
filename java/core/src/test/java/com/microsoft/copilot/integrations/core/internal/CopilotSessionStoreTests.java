package com.microsoft.copilot.integrations.core.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.UserMessageEvent;
import com.github.copilot.rpc.SessionMetadata;
import com.microsoft.copilot.integrations.core.CopilotSessionMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CopilotSessionStoreTests {

    private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");
    private static final String OLD = "agent-old";
    private static final String NEW = "agent-new";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FakeStorage storage = new FakeStorage();
    private final CopilotAgentInvoker invoker = new CopilotAgentInvoker(null);
    private final DefaultCopilotSessionStore store = new DefaultCopilotSessionStore(storage, invoker, type -> null);

    @AfterEach
    void close() {
        invoker.close();
    }

    @Test
    void listsOnlyLocalAgentSessionsMostRecentFirst() {
        storage.put(session(OLD, Duration.ofDays(3), false));
        storage.put(session(NEW, Duration.ofMinutes(1), false));
        storage.put(session("agent-remote", Duration.ofMinutes(1), true));
        storage.put(session("user-session", Duration.ofMinutes(1), false));
        storage.put(session("agent-undated", null, false));

        assertThat(store.list()).extracting(SessionMetadata::getSessionId)
                .containsExactly(NEW, OLD, "agent-undated");
    }

    @Test
    void getsAndChecksExistence() {
        storage.put(session(OLD, Duration.ZERO, false));

        assertThat(store.get(OLD)).map(SessionMetadata::getSessionId).contains(OLD);
        assertThat(store.exists(OLD)).isTrue();
        assertThat(store.get("agent-missing")).isEmpty();
        assertThat(store.exists("agent-missing")).isFalse();
        assertThatThrownBy(() -> store.get(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsUserAndAssistantMessagesWithoutSubAgentsOrEmptyReplies() {
        storage.put(session(OLD, Duration.ZERO, false));
        var timestamp = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        storage.events.put(OLD, List.of(
                user("hello", "u1", null, timestamp),
                assistant("", "a0", null),
                assistant("from sub-agent", "a1", "explore"),
                user("sub-agent prompt", "u2", "explore", timestamp),
                assistant("hi there", "a2", null)));

        assertThat(store.messages(OLD)).hasValueSatisfying(messages -> assertThat(messages).containsExactly(
                new CopilotSessionMessage(CopilotSessionMessage.Role.USER, "hello", "u1", timestamp),
                new CopilotSessionMessage(CopilotSessionMessage.Role.ASSISTANT, "hi there", "a2", null)));
        assertThat(store.messages("agent-missing")).isEmpty();
    }

    @Test
    void deletesExistingSessionsAndReportsMissingOnes() {
        storage.put(session(OLD, Duration.ZERO, false));

        assertThat(store.delete(OLD)).isTrue();
        assertThat(storage.deleted).containsExactly(OLD);
        assertThat(store.delete(OLD)).isFalse();
        assertThat(storage.deleted).containsExactly(OLD);
    }

    @Test
    void deleteWaitsForTheTurnInFlightAndHoldsNewTurns() throws Exception {
        storage.put(session(OLD, Duration.ZERO, false));
        var turn = new CompletableFuture<Void>();
        invoker.exclusive(OLD, () -> turn);

        var deletion = CompletableFuture.supplyAsync(() -> store.delete(OLD));
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(deletion).isNotDone();
        assertThat(storage.deleted).isEmpty();
        var queued = new CompletableFuture<Boolean>();
        invoker.exclusive(OLD, () -> {
            queued.complete(storage.deleted.contains(OLD));
            return CompletableFuture.completedFuture(null);
        });

        turn.complete(null);
        assertThat(deletion.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queued.get(5, TimeUnit.SECONDS)).as("the next turn starts after the deletion").isTrue();
    }

    @Test
    void abandonedDeletionDoesNotRunLater() throws Exception {
        storage.put(session(OLD, Duration.ZERO, false));
        var turn = new CompletableFuture<Void>();
        invoker.exclusive(OLD, () -> turn);
        var impatient = new DefaultCopilotSessionStore(storage, invoker, type -> null, Duration.ofMillis(50));

        assertThatThrownBy(() -> impatient.delete(OLD)).hasMessageContaining("did not respond");
        turn.complete(null);
        invoker.exclusive(OLD, () -> CompletableFuture.completedFuture(null)).get(5, TimeUnit.SECONDS);
        assertThat(storage.deleted).isEmpty();
    }

    @Test
    void sessionIdRequiresADiscoveredAgent() {
        assertThatThrownBy(() -> store.sessionId(Runnable.class, "c1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not discovered");
    }

    @Test
    void cleanerDeletesOnlyExpiredLocalAgentSessions() {
        storage.put(session(OLD, Duration.ofDays(2), false));
        storage.put(session(NEW, Duration.ofHours(1), false));
        storage.put(session("agent-remote", Duration.ofDays(2), true));
        storage.put(session("user-session", Duration.ofDays(2), false));
        storage.put(session("agent-undated", null, false));

        assertThat(cleaner(Duration.ofDays(1)).sweep()).isEqualTo(1);
        assertThat(storage.deleted).containsExactly(OLD);
    }

    @Test
    void cleanerNeverStartsTheClient() {
        storage.started = false;
        storage.put(session(OLD, Duration.ofDays(2), false));

        assertThat(cleaner(Duration.ofDays(1)).sweep()).isZero();
        assertThat(storage.listed).isZero();
    }

    @Test
    void cleanerSkipsSessionsWithATurnInFlight() {
        storage.put(session(OLD, Duration.ofDays(2), false));
        var turn = new CompletableFuture<Void>();
        invoker.exclusive(OLD, () -> turn);

        assertThat(cleaner(Duration.ofDays(1)).sweep()).isZero();
        turn.complete(null);
        assertThat(cleaner(Duration.ofDays(1)).sweep()).isEqualTo(1);
    }

    @Test
    void cleanerRechecksTheSessionBeforeDeleting() {
        storage.put(session(OLD, Duration.ofDays(2), false));
        storage.onGet = () -> storage.put(session(OLD, Duration.ZERO, false));

        assertThat(cleaner(Duration.ofDays(1)).sweep()).isZero();
        assertThat(storage.deleted).isEmpty();
    }

    @Test
    void cleanerContinuesAfterADeleteFailure() {
        storage.put(session("agent-a", Duration.ofDays(2), false));
        storage.put(session("agent-b", Duration.ofDays(2), false));
        storage.failDelete = "agent-a";

        assertThat(cleaner(Duration.ofDays(1)).sweep()).isEqualTo(1);
        assertThat(storage.deleted).containsExactly("agent-b");
    }

    @Test
    void quietSweepSwallowsListingFailures() {
        storage.failList = true;
        var cleaner = cleaner(Duration.ofDays(1));

        assertThatThrownBy(cleaner::sweep).isInstanceOf(IllegalStateException.class);
        cleaner.sweepQuietly();
    }

    @Test
    void intervalDefaultsToHalfTheMaxAgeWithinBounds() {
        assertThat(CopilotSessionCleaner.interval(Duration.ofHours(1), null)).isEqualTo(Duration.ofMinutes(30));
        assertThat(CopilotSessionCleaner.interval(Duration.ofSeconds(30), null)).isEqualTo(Duration.ofMinutes(1));
        assertThat(CopilotSessionCleaner.interval(Duration.ofDays(30), null)).isEqualTo(Duration.ofHours(1));
        assertThat(CopilotSessionCleaner.interval(Duration.ofDays(30), Duration.ofMinutes(5)))
                .isEqualTo(Duration.ofMinutes(5));
        assertThatThrownBy(() -> CopilotSessionCleaner.interval(Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CopilotSessionCleaner cleaner(Duration maxAge) {
        return new CopilotSessionCleaner(storage, invoker, maxAge, Duration.ofSeconds(5),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SessionMetadata session(String id, Duration age, boolean remote) {
        var metadata = new SessionMetadata();
        metadata.setSessionId(id);
        metadata.setRemote(remote);
        if (age != null) {
            metadata.setModifiedTime(NOW.minus(age).toString());
        }
        return metadata;
    }

    private static SessionEvent user(String content, String id, String agentId, OffsetDateTime timestamp) {
        var event = new UserMessageEvent();
        event.setData(JSON.convertValue(Map.of("content", content, "messageId", id),
                UserMessageEvent.UserMessageEventData.class));
        event.setAgentId(agentId);
        event.setTimestamp(timestamp);
        return event;
    }

    private static SessionEvent assistant(String content, String id, String agentId) {
        var event = new AssistantMessageEvent();
        event.setData(JSON.convertValue(Map.of("content", content, "messageId", id),
                AssistantMessageEvent.AssistantMessageEventData.class));
        event.setAgentId(agentId);
        return event;
    }

    private static final class FakeStorage implements CopilotSessionStorage {

        final Map<String, SessionMetadata> sessions = new ConcurrentHashMap<>();
        final Map<String, List<SessionEvent>> events = new ConcurrentHashMap<>();
        final List<String> deleted = new CopyOnWriteArrayList<>();
        volatile boolean started = true;
        volatile boolean failList;
        volatile String failDelete;
        volatile Runnable onGet;
        volatile int listed;

        void put(SessionMetadata session) {
            sessions.put(session.getSessionId(), session);
        }

        @Override
        public boolean isStarted() {
            return started;
        }

        @Override
        public CompletableFuture<List<SessionMetadata>> list() {
            listed++;
            return failList ? CompletableFuture.failedFuture(new IllegalStateException("boom"))
                    : CompletableFuture.completedFuture(new ArrayList<>(sessions.values()));
        }

        @Override
        public CompletableFuture<SessionMetadata> get(String sessionId) {
            var hook = onGet;
            if (hook != null) {
                hook.run();
            }
            return CompletableFuture.completedFuture(sessions.get(sessionId));
        }

        @Override
        public CompletableFuture<List<SessionEvent>> readEvents(String sessionId) {
            return CompletableFuture.completedFuture(events.getOrDefault(sessionId, List.of()));
        }

        @Override
        public CompletableFuture<Void> delete(String sessionId) {
            if (sessionId.equals(failDelete)) {
                return CompletableFuture.failedFuture(new IllegalStateException("cannot delete"));
            }
            sessions.remove(sessionId);
            deleted.add(sessionId);
            return CompletableFuture.completedFuture(null);
        }
    }
}
