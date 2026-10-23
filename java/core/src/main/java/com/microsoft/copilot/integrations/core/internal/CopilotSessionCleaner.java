package com.microsoft.copilot.integrations.core.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.copilot.rpc.SessionMetadata;

/**
 * Deletes agent sessions not modified within a maximum age. Frameworks call {@link #sweep()} from their native
 * scheduler every {@link #interval(Duration, Duration)}.
 *
 * <p>Deletion is conservative: only local sessions whose ID carries the
 * {@linkplain CopilotAgentInvoker#MANAGED_SESSION_PREFIX agent prefix} are considered; a sweep is skipped while the
 * client is not connected, so cleanup never starts the runtime on its own; sessions with a turn queued or running are
 * skipped until the next sweep; and each candidate's metadata is read again right before it is deleted.
 */
public final class CopilotSessionCleaner {

    private static final Logger log = Logger.getLogger(CopilotSessionCleaner.class.getName());
    private static final Duration MIN_INTERVAL = Duration.ofMinutes(1);
    private static final Duration MAX_INTERVAL = Duration.ofHours(1);

    private final CopilotSessionStorage storage;
    private final CopilotAgentInvoker invoker;
    private final Duration maxAge;
    private final Duration timeout;
    private final Clock clock;

    public CopilotSessionCleaner(CopilotSessionStorage storage, CopilotAgentInvoker invoker, Duration maxAge) {
        this(storage, invoker, maxAge, DefaultCopilotSessionStore.DEFAULT_TIMEOUT, Clock.systemUTC());
    }

    public CopilotSessionCleaner(CopilotSessionStorage storage, CopilotAgentInvoker invoker, Duration maxAge,
            Duration timeout, Clock clock) {
        Assert.notNull(storage, "Copilot session storage is required");
        Assert.notNull(invoker, "CopilotAgentInvoker is required");
        Assert.isTrue(maxAge != null && maxAge.isPositive(), "Copilot session max age must be positive");
        Assert.isTrue(timeout != null && timeout.isPositive(), "Copilot session cleanup timeout must be positive");
        Assert.notNull(clock, "Clock is required");
        this.storage = storage;
        this.invoker = invoker;
        this.maxAge = maxAge;
        this.timeout = timeout;
        this.clock = clock;
    }

    /**
     * Returns {@code configured} when set, otherwise half of {@code maxAge} clamped to between one minute and one
     * hour.
     */
    public static Duration interval(Duration maxAge, Duration configured) {
        if (configured != null) {
            Assert.isTrue(configured.isPositive(), "Copilot session cleanup interval must be positive");
            return configured;
        }
        Assert.isTrue(maxAge != null && maxAge.isPositive(), "Copilot session max age must be positive");
        var half = maxAge.dividedBy(2);
        return half.compareTo(MIN_INTERVAL) < 0 ? MIN_INTERVAL : half.compareTo(MAX_INTERVAL) > 0 ? MAX_INTERVAL : half;
    }

    public Duration maxAge() {
        return maxAge;
    }

    /**
     * Deletes expired agent sessions once. Failures to delete one session are logged and do not stop the sweep.
     *
     * @return the number of sessions deleted
     * @throws IllegalStateException if the sessions cannot be listed
     */
    public int sweep() {
        if (!storage.isStarted()) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(maxAge);
        List<SessionMetadata> sessions = await(storage.list());
        int deleted = 0;
        for (var session : sessions == null ? List.<SessionMetadata>of() : sessions) {
            if (session == null || !expired(session, cutoff)) {
                continue;
            }
            String sessionId = session.getSessionId();
            try {
                var deletion = invoker.tryExclusive(sessionId, () -> storage.get(sessionId).thenCompose(current ->
                        current == null || !expired(current, cutoff) ? CompletableFuture.completedFuture(false)
                                : storage.delete(sessionId).thenApply(ignored -> true)));
                if (deletion != null && Boolean.TRUE.equals(await(deletion))) {
                    deleted++;
                }
            } catch (RuntimeException ex) {
                log.log(Level.WARNING, "Could not delete expired Copilot session " + sessionId, ex);
            }
        }
        if (deleted > 0) {
            int count = deleted;
            log.info(() -> "Deleted " + count + " Copilot sessions not modified since " + cutoff);
        }
        return deleted;
    }

    /** Runs {@link #sweep()} and logs, rather than throws, a failure so a scheduler keeps the next run. */
    public void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "Copilot session cleanup failed; retrying at the next sweep", ex);
        }
    }

    private static boolean expired(SessionMetadata session, Instant cutoff) {
        if (!DefaultCopilotSessionStore.managedLocal(session)) {
            return false;
        }
        Instant modified = DefaultCopilotSessionStore.modified(session);
        return modified != null && modified.isBefore(cutoff);
    }

    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Copilot session cleanup", ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("Copilot runtime did not respond within " + timeout, ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Copilot session cleanup call failed", ex.getCause());
        }
    }
}
