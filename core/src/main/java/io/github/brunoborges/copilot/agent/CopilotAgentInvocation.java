package io.github.brunoborges.copilot.agent;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Starts one streaming invocation; the future signals completion, not a required text result. */
@FunctionalInterface
public interface CopilotAgentInvocation {
    CompletableFuture<?> invoke(Consumer<CopilotAgentEvent> events);
}
