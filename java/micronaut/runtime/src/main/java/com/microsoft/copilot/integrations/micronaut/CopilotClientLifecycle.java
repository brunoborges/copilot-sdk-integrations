package com.microsoft.copilot.integrations.micronaut;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.github.copilot.CopilotClient;

final class CopilotClientLifecycle {

    private CopilotClientLifecycle() {
    }

    static void start(CopilotClient client, Duration timeout) {
        try {
            client.start().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            try {
                throw failure(client, "Interrupted during eager Copilot startup", ex);
            } finally {
                Thread.currentThread().interrupt();
            }
        } catch (TimeoutException ex) {
            throw failure(client, "Eager Copilot startup exceeded micronaut.copilot.startup-timeout", ex);
        } catch (ExecutionException | RuntimeException ex) {
            throw failure(client, "Eager Copilot startup failed", ex);
        }
    }

    private static IllegalStateException failure(CopilotClient client, String message, Exception cause) {
        try {
            client.close();
        } catch (RuntimeException cleanupFailure) {
            cause.addSuppressed(cleanupFailure);
        }
        return new IllegalStateException(message, cause);
    }
}
