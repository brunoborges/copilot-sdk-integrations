package com.microsoft.copilot.integrations.micronaut.reactor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.github.copilot.generated.SessionIdleEvent;
import com.microsoft.copilot.integrations.core.CopilotAgentContext;
import com.microsoft.copilot.integrations.core.CopilotAgentEvent;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ReactorCopilotAgentAdapterTest {

    private final ReactorCopilotAgentAdapter adapter = new ReactorCopilotAgentAdapter(4);
    private final CopilotAgentEvent first = event("first");
    private final CopilotAgentEvent second = event("second");

    @Test
    void streamIsColdAndEverySubscriptionStartsAnIndependentInvocation() {
        var started = new AtomicInteger();
        var flux = adapter.adapt(events -> {
            started.incrementAndGet();
            events.accept(first);
            events.accept(second);
            return CompletableFuture.completedFuture("reply");
        });
        assertThat(started.get()).isZero();
        StepVerifier.create(flux).expectNext(first, second).verifyComplete();
        StepVerifier.create(flux).expectNext(first, second).verifyComplete();
        assertThat(started.get()).isEqualTo(2);
    }

    @Test
    void overflowFailsAfterBufferedEventsDrainAndCancelsTheInvocationResult() {
        var bounded = new ReactorCopilotAgentAdapter(1);
        var operation = new CompletableFuture<String>();
        var flux = bounded.adapt(events -> {
            events.accept(first);
            events.accept(second);
            return operation;
        });
        StepVerifier.create(flux, 0).thenRequest(1).expectNext(first)
                .expectError(CopilotAgentStreamOverflowException.class).verify();
        assertThat(operation.isCancelled()).isTrue();
    }

    @Test
    void cancellationStopsDeliveryAndCancelsOnlyTheInvocationFuture() {
        var callback = new AtomicReference<Consumer<CopilotAgentEvent>>();
        var operation = new CompletableFuture<String>();
        var delivered = new AtomicInteger();
        var subscription = adapter.adapt(events -> {
            callback.set(events);
            return operation;
        }).subscribe(event -> delivered.incrementAndGet());
        callback.get().accept(first);
        subscription.dispose();
        callback.get().accept(second);
        assertThat(delivered.get()).isEqualTo(1);
        assertThat(operation.isCancelled()).isTrue();
    }

    @Test
    void failedProducerPreservesEventsAndPropagatesItsCause() {
        var failure = new IllegalStateException("runtime failed");
        StepVerifier.create(adapter.adapt(events -> {
            events.accept(first);
            return CompletableFuture.failedFuture(new java.util.concurrent.CompletionException(failure));
        })).expectNext(first).expectErrorMatches(error -> error == failure).verify();
    }

    @Test
    void synchronousProducerFailureIsAnErrorSignal() {
        StepVerifier.create(adapter.adapt(events -> {
            throw new IllegalArgumentException("cannot start");
        })).expectErrorMessage("cannot start").verify();
    }

    private static CopilotAgentEvent event(String conversationId) {
        return new CopilotAgentEvent(new CopilotAgentContext("agent", conversationId, "sdk-session"), new SessionIdleEvent());
    }
}
