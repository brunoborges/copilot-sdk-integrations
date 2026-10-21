package io.github.brunoborges.copilot.autoconfigure;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import com.github.copilot.CopilotClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CopilotClientLifecycleTests {

    private final CopilotClient client = mock(CopilotClient.class);

    @Test
    void successDoesNotCloseClientBeforeContextShutdown() {
        when(client.start()).thenReturn(CompletableFuture.completedFuture(null));
        CopilotClientLifecycle.start(client, Duration.ofSeconds(1));
        verify(client, never()).close();
    }

    @Test
    void failedStartupRetainsCauseAndClosesClient() {
        var cause = new IllegalArgumentException("startup failed");
        when(client.start()).thenReturn(CompletableFuture.failedFuture(cause));
        assertThatThrownBy(() -> CopilotClientLifecycle.start(client, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Eager Copilot startup failed")
                .hasCauseInstanceOf(ExecutionException.class).hasRootCause(cause);
        verify(client).close();
    }

    @Test
    void timeoutClosesClient() {
        when(client.start()).thenReturn(new CompletableFuture<>());
        assertThatIllegalStateException().isThrownBy(() -> CopilotClientLifecycle.start(client, Duration.ofMillis(1)))
                .withMessageContaining("copilot.startup-timeout").withCauseInstanceOf(TimeoutException.class);
        verify(client).close();
    }

    @Test
    void interruptionClosesClientAndRestoresInterruptFlag() {
        when(client.start()).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThatIllegalStateException()
                    .isThrownBy(() -> CopilotClientLifecycle.start(client, Duration.ofSeconds(1)))
                    .withCauseInstanceOf(InterruptedException.class);
            verify(client).close();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void synchronousStartupFailureAlsoClosesClient() {
        var cause = new IllegalStateException("synchronous failure");
        when(client.start()).thenThrow(cause);
        assertThatIllegalStateException().isThrownBy(() -> CopilotClientLifecycle.start(client, Duration.ofSeconds(1)))
                .withCause(cause);
        verify(client).close();
    }

    @Test
    void cleanupFailureDoesNotHideStartupFailure() {
        var cause = new IllegalStateException("startup failed");
        var cleanupFailure = new IllegalStateException("cleanup failed");
        when(client.start()).thenThrow(cause);
        doThrow(cleanupFailure).when(client).close();
        assertThatIllegalStateException().isThrownBy(() -> CopilotClientLifecycle.start(client, Duration.ofSeconds(1)))
                .withCause(cause);
        assertThat(cause.getSuppressed()).containsExactly(cleanupFailure);
    }
}
