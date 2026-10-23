package io.github.brunoborges.copilot.agent.internal;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.copilot.CopilotSession;
import com.github.copilot.rpc.MessageSource;

/**
 * Per-invocation state captured on the caller thread and the cancellation handshake between the caller and
 * the worker that owns the SDK session.
 */
final class InvocationState {

    static final Duration ABORT_TIMEOUT = Duration.ofSeconds(10);
    private static final Logger log = Logger.getLogger(InvocationState.class.getName());

    private final String gitHubToken;
    private final ContextPropagation.Snapshot context;
    private final MessageSource messageSource;
    private final Object lock = new Object();
    private boolean cancelled;
    private CompletableFuture<?> waiting;

    InvocationState(String gitHubToken, ContextPropagation.Snapshot context) {
        this(gitHubToken, context, null);
    }

    InvocationState(String gitHubToken, ContextPropagation.Snapshot context, MessageSource messageSource) {
        this.gitHubToken = gitHubToken;
        this.context = context;
        this.messageSource = messageSource;
    }

    /** The prompt's provenance, or {@code null} to omit it. */
    MessageSource messageSource() {
        return messageSource;
    }

    String gitHubToken() {
        return gitHubToken;
    }

    ContextPropagation.Snapshot context() {
        return context;
    }

    /** Marks the invocation cancelled and stops the worker from waiting on the in-flight response. */
    void cancel() {
        CompletableFuture<?> current;
        synchronized (lock) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            current = waiting;
        }
        if (current != null) {
            current.cancel(false);
        }
    }

    boolean cancelled() {
        synchronized (lock) {
            return cancelled;
        }
    }

    void checkCancelled() {
        if (cancelled()) {
            throw new CancellationException("Copilot agent invocation was cancelled");
        }
    }

    /** Registers the in-flight response; cancels it immediately when the caller already cancelled. */
    <T> CompletableFuture<T> awaiting(CompletableFuture<T> response) {
        synchronized (lock) {
            if (!cancelled) {
                waiting = response;
                return response;
            }
        }
        response.cancel(false);
        return response;
    }

    /**
     * Waits for a session-opening operation while honouring cancellation and a bounded timeout. When the wait is
     * abandoned, a value that still arrives later is handed to {@code abandoned} so it can be released.
     */
    <T> T join(CompletableFuture<T> operation, Duration timeout, Consumer<? super T> abandoned) {
        var view = awaiting(operation.copy());
        try {
            return view.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException ex) {
            throw new CompletionException(ex.getCause());
        } catch (CancellationException ex) {
            release(operation, abandoned);
            throw ex;
        } catch (TimeoutException ex) {
            view.cancel(false);
            release(operation, abandoned);
            throw new CompletionException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            view.cancel(false);
            release(operation, abandoned);
            var cancelled = new CancellationException("Interrupted while opening the Copilot session");
            cancelled.initCause(ex);
            throw cancelled;
        }
    }

    private static <T> void release(CompletableFuture<T> operation, Consumer<? super T> abandoned) {
        if (abandoned == null) {
            return;
        }
        operation.thenAccept(value -> {
            try {
                if (value != null) {
                    abandoned.accept(value);
                }
            } catch (RuntimeException ex) {
                log.log(Level.WARNING, "Failed to release an abandoned Copilot session", ex);
            }
        });
    }

    /** Best-effort runtime abort of the in-flight turn; failures are logged, never thrown. */
    static void abort(CopilotSession session, String agent) {
        try {
            var acknowledged = session.abort();
            if (acknowledged != null) {
                acknowledged.get(ABORT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.log(Level.WARNING, "Interrupted while aborting Copilot agent '" + agent + "' turn", ex);
        } catch (Exception ex) {
            log.log(Level.WARNING, "Failed to abort Copilot agent '" + agent + "' turn", ex);
        }
    }

    @Override
    public String toString() {
        return "InvocationState[gitHubToken=" + (gitHubToken == null ? "<ambient>" : "<redacted>")
                + ", cancelled=" + cancelled() + "]";
    }
}
