package io.github.brunoborges.copilot.micronaut.reactor;

public final class CopilotAgentStreamOverflowException extends IllegalStateException {
    public CopilotAgentStreamOverflowException(int capacity) {
        super("Copilot agent stream buffer capacity exceeded (" + capacity + " events)");
    }
}
