package io.github.brunoborges.copilot.agent.internal;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvocationStateTests {

    @Test
    void neverRendersTheToken() {
        assertThat(new InvocationState("ghu_secret", ContextPropagation.Snapshot.NONE).toString())
                .doesNotContain("ghu_secret").contains("<redacted>");
        assertThat(new InvocationState(null, ContextPropagation.Snapshot.NONE).toString()).contains("<ambient>");
    }

    @Test
    void cancellationBeforeAwaitingCancelsTheResponseAndIsIdempotent() {
        var state = new InvocationState(null, ContextPropagation.Snapshot.NONE);
        var waiting = state.awaiting(new CompletableFuture<String>());
        state.cancel();
        state.cancel();
        assertThat(waiting.isCancelled()).isTrue();
        assertThatThrownBy(state::checkCancelled).isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(state.awaiting(new CompletableFuture<String>()).isCancelled()).isTrue();
    }
}
