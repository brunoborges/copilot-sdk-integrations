package io.github.brunoborges.copilot.micronaut;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.github.copilot.CopilotClient;
import com.github.copilot.ConnectionState;

/** Guards runtime calls so diagnostics never implicitly start a lazily configured client. */
public final class CopilotRuntimeAccess {

    public static final String NOT_STARTED = "Copilot client has not been started; it connects on first use";
    public static final String CONNECTING = "Copilot client is connecting";
    public static final String FAILED = "Copilot client connection failed";

    private CopilotRuntimeAccess() {
    }

    /**
     * Returns {@code null} when runtime RPCs may be issued, otherwise the reason they must not be.
     * SDK RPC methods start a disconnected client implicitly, so callers must check this first.
     */
    public static String connect(CopilotClient client, boolean startClient, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        ConnectionState state = client.getState();
        if (state == ConnectionState.CONNECTED) {
            return null;
        }
        if (state == ConnectionState.ERROR) {
            return FAILED;
        }
        if (!startClient) {
            return state == ConnectionState.CONNECTING ? CONNECTING : NOT_STARTED;
        }
        await(client.start(), deadline);
        return client.getState() == ConnectionState.CONNECTED ? null : FAILED;
    }

    public static <T> T await(CompletableFuture<T> future, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    public static String state(CopilotClient client) {
        try {
            ConnectionState state = client.getState();
            return state == null ? "UNKNOWN" : state.name();
        } catch (RuntimeException ex) {
            return "UNKNOWN";
        }
    }

    /** Exposes only the exception type; runtime messages may echo connection details. */
    public static String error(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getName();
    }
}
