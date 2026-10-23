package io.github.brunoborges.copilot.agent.internal;

import com.github.copilot.rpc.MessageSource;

/**
 * Thread-local holder behind {@link io.github.brunoborges.copilot.agent.CopilotMessageSources} scopes. The invoker
 * clears it while another agent's tool handler runs so an outer caller's scope never relabels delegated calls.
 */
public final class MessageSourceScope {

    private static final ThreadLocal<MessageSource> CURRENT = new ThreadLocal<>();

    private MessageSourceScope() {
    }

    public static MessageSource current() {
        return CURRENT.get();
    }

    /** Sets {@code source} (or clears it when {@code null}) and returns the previous value. */
    public static MessageSource swap(MessageSource source) {
        var previous = CURRENT.get();
        if (source == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(source);
        }
        return previous;
    }
}
