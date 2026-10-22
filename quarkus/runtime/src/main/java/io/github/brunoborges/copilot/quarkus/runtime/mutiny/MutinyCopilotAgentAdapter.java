package io.github.brunoborges.copilot.quarkus.runtime.mutiny;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.github.brunoborges.copilot.agent.CopilotAgentEvent;
import io.github.brunoborges.copilot.agent.CopilotAgentInvocation;
import io.github.brunoborges.copilot.agent.CopilotAgentReturnTypeAdapter;
import io.github.brunoborges.copilot.quarkus.runtime.config.CopilotRuntimeConfig;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.BackPressureFailure;
import io.smallrye.mutiny.subscription.MultiEmitter;

/**
 * Supports agent methods returning {@code Multi<CopilotAgentEvent>}. Each subscription starts one invocation;
 * cancellation cancels it. Events are buffered up to {@code quarkus.copilot.streaming.buffer-capacity}; a
 * subscriber falling further behind fails with {@link CopilotAgentStreamOverflowException}.
 */
@Singleton
public class MutinyCopilotAgentAdapter implements CopilotAgentReturnTypeAdapter {

    private final int capacity;

    @Inject
    public MutinyCopilotAgentAdapter(CopilotRuntimeConfig config) {
        this(config.streaming().bufferCapacity());
    }

    public MutinyCopilotAgentAdapter(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("quarkus.copilot.streaming.buffer-capacity must be positive");
        }
        this.capacity = capacity;
    }

    @Override
    public boolean supports(Type type) {
        return type instanceof ParameterizedType parameterized
                && parameterized.getRawType() == Multi.class
                && parameterized.getActualTypeArguments()[0] == CopilotAgentEvent.class;
    }

    @Override
    public Multi<CopilotAgentEvent> adapt(CopilotAgentInvocation invocation) {
        return Multi.createFrom().<CopilotAgentEvent> emitter(emitter -> start(invocation, emitter), capacity)
                .onFailure(BackPressureFailure.class)
                .transform(failure -> new CopilotAgentStreamOverflowException(capacity));
    }

    private static void start(CopilotAgentInvocation invocation, MultiEmitter<? super CopilotAgentEvent> emitter) {
        var future = new AtomicReference<CompletableFuture<?>>();
        emitter.onTermination(() -> {
            var current = future.get();
            if (current != null && !current.isDone()) {
                current.cancel(true);
            }
        });
        try {
            var started = invocation.invoke(event -> {
                if (!emitter.isCancelled()) {
                    emitter.emit(event);
                }
            });
            future.set(started);
            if (emitter.isCancelled()) {
                started.cancel(true);
            }
            started.whenComplete((response, error) -> {
                if (error == null) {
                    emitter.complete();
                } else {
                    Throwable cause = error;
                    while (cause instanceof CompletionException && cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    emitter.fail(cause);
                }
            });
        } catch (RuntimeException ex) {
            emitter.fail(ex);
        }
    }
}
