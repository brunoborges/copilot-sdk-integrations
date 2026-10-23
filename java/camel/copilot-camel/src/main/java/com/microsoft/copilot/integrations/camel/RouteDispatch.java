package com.microsoft.copilot.integrations.camel;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultConsumer;

/** Runs a Copilot callback (tool call, permission request, event) through the route of a consumer. */
final class RouteDispatch {

    private RouteDispatch() {
    }

    /**
     * Routes a new exchange prepared by {@code prepare} and maps the completed exchange with {@code result}; the
     * future fails with the exchange's exception, if any.
     */
    static <T> CompletableFuture<T> dispatch(DefaultConsumer consumer, Consumer<Exchange> prepare,
            Function<Exchange, T> result) {
        var future = new CompletableFuture<T>();
        Exchange exchange = consumer.createExchange(false);
        try {
            prepare.accept(exchange);
            consumer.getAsyncProcessor().process(exchange, doneSync -> complete(consumer, exchange, result, future));
        } catch (RuntimeException ex) {
            exchange.setException(ex);
            complete(consumer, exchange, result, future);
        }
        return future;
    }

    private static <T> void complete(DefaultConsumer consumer, Exchange exchange, Function<Exchange, T> result,
            CompletableFuture<T> future) {
        try {
            Exception failure = exchange.getException();
            if (failure != null) {
                future.completeExceptionally(failure);
            } else {
                future.complete(result.apply(exchange));
            }
        } catch (RuntimeException ex) {
            future.completeExceptionally(ex);
        } finally {
            consumer.releaseExchange(exchange, false);
        }
    }
}
