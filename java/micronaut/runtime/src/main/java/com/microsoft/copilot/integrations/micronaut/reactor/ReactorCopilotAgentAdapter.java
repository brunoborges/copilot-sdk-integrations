package io.github.brunoborges.copilot.micronaut.reactor;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.github.brunoborges.copilot.agent.CopilotAgentEvent;
import io.github.brunoborges.copilot.agent.CopilotAgentInvocation;
import io.github.brunoborges.copilot.agent.CopilotAgentReturnTypeAdapter;
import io.github.brunoborges.copilot.micronaut.config.CopilotRuntimeConfig;
import io.micronaut.context.annotation.Requires;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Lets agent methods return {@code Flux<CopilotAgentEvent>}. Events are buffered up to
 * {@code micronaut.copilot.streaming.buffer-capacity}; a slower subscriber fails the stream with
 * {@link CopilotAgentStreamOverflowException}, and cancelling the subscription cancels the turn.
 */
@Singleton
@Requires(classes = Flux.class)
@Requires(property = CopilotRuntimeConfig.PREFIX + ".enabled", notEquals = "false")
public final class ReactorCopilotAgentAdapter implements CopilotAgentReturnTypeAdapter {

    private final int capacity;

    @Inject
    public ReactorCopilotAgentAdapter(CopilotRuntimeConfig config) {
        this(config.streaming().bufferCapacity());
    }

    public ReactorCopilotAgentAdapter(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("micronaut.copilot.streaming.buffer-capacity must be positive");
        }
        this.capacity = capacity;
    }

    @Override
    public boolean supports(Type type) {
        return type instanceof ParameterizedType parameterized
                && parameterized.getRawType() == Flux.class
                && parameterized.getActualTypeArguments()[0] == CopilotAgentEvent.class;
    }

    @Override
    public Flux<CopilotAgentEvent> adapt(CopilotAgentInvocation invocation) {
        return Flux.defer(() -> new Subscription(invocation).flux());
    }

    private final class Subscription {
        private final CopilotAgentInvocation invocation;
        private final Sinks.Many<CopilotAgentEvent> sink =
                Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(capacity));
        private final AtomicReference<CompletableFuture<?>> future = new AtomicReference<>();
        private final AtomicBoolean done = new AtomicBoolean();
        private final Object emissionLock = new Object();

        private Subscription(CopilotAgentInvocation invocation) {
            this.invocation = invocation;
        }

        private Flux<CopilotAgentEvent> flux() {
            return sink.asFlux().doOnSubscribe(subscription -> start()).doOnCancel(this::cancel);
        }

        private void start() {
            try {
                var started = invocation.invoke(this::next);
                future.set(started);
                if (done.get()) {
                    started.cancel(true);
                }
                started.whenComplete((response, error) -> complete(error));
            } catch (RuntimeException ex) {
                complete(ex);
            }
        }

        private void next(CopilotAgentEvent event) {
            synchronized (emissionLock) {
                if (done.get()) {
                    return;
                }
                var emitted = sink.tryEmitNext(event);
                if (emitted == Sinks.EmitResult.FAIL_OVERFLOW) {
                    fail(new CopilotAgentStreamOverflowException(capacity));
                } else if (emitted == Sinks.EmitResult.FAIL_CANCELLED || emitted == Sinks.EmitResult.FAIL_TERMINATED) {
                    cancel();
                } else if (emitted.isFailure()) {
                    fail(new IllegalStateException("Cannot emit SDK event to Copilot stream: " + emitted));
                }
            }
        }

        private void fail(Throwable error) {
            if (done.compareAndSet(false, true)) {
                sink.tryEmitError(error);
                cancelFuture();
            }
        }

        private void complete(Throwable error) {
            if (done.get()) {
                return;
            }
            synchronized (emissionLock) {
                if (done.compareAndSet(false, true)) {
                    if (error == null) {
                        sink.tryEmitComplete();
                    } else {
                        Throwable cause = error;
                        while (cause instanceof CompletionException && cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        sink.tryEmitError(cause);
                    }
                }
            }
        }

        private void cancel() {
            done.set(true);
            cancelFuture();
        }

        private void cancelFuture() {
            var current = future.get();
            if (current != null) {
                current.cancel(true);
            }
        }
    }
}
