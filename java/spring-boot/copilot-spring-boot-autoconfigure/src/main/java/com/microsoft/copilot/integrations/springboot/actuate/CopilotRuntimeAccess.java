package com.microsoft.copilot.integrations.springboot.actuate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.github.copilot.ConnectionState;
import com.github.copilot.CopilotClient;

/** Guards runtime calls so diagnostics never implicitly start a lazily configured client. */
final class CopilotRuntimeAccess {

    static final String NOT_STARTED = "Copilot client has not been started; it connects on first use";
    static final String CONNECTING = "Copilot client is connecting";
    static final String FAILED = "Copilot client connection failed";

    private CopilotRuntimeAccess() {
    }

    /**
     * Returns {@code null} when runtime RPCs may be issued, otherwise the reason they must not be.
     * SDK RPC methods start a disconnected client implicitly, so callers must check this first.
     */
    static String connect(CopilotClient client, boolean startClient, long deadline)
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

    static <T> T await(CompletableFuture<T> future, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    static String state(CopilotClient client) {
        ConnectionState state = client.getState();
        return state == null ? "UNKNOWN" : state.name();
    }

    /** Exposes only the exception type; runtime messages may echo connection details. */
    static String error(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof java.util.concurrent.CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getName();
    }
}
