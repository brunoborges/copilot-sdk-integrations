package io.github.brunoborges.copilot.quarkus.runtime.mutiny;

/** Signals that a {@code Multi<CopilotAgentEvent>} subscriber fell behind beyond the configured buffer capacity. */
public final class CopilotAgentStreamOverflowException extends IllegalStateException {

    public CopilotAgentStreamOverflowException(int capacity) {
        super("Copilot agent stream buffer capacity exceeded (" + capacity + " events)");
    }
}
