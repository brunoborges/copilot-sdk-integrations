package com.microsoft.copilot.integrations.core;

import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.github.copilot.rpc.MessageSource;
import com.microsoft.copilot.integrations.core.internal.MessageSourceScope;

/**
 * Labels the provenance of the prompts that {@link CopilotAgent} methods send, using the SDK {@link MessageSource}
 * ({@code user}, {@code system}, or {@code agent-<id>}).
 *
 * <p>Each invocation's source is chosen, in order of precedence, from:
 * <ol>
 * <li>a {@link #callAs(MessageSource, Supplier)} or {@link #runAs(MessageSource, Runnable)} scope active on the
 * calling thread when the agent method is called (inside a tool handler, only a scope opened by the handler itself
 * counts; scopes of the outer caller are suspended);</li>
 * <li>{@code agent-<caller>} when the agent method is called on the thread running a tool handler of another
 * agent named {@code <caller>} (agent-to-agent delegation within this library); calls a tool hands off to
 * another thread are not detected;</li>
 * <li>{@code copilot.agents.<name>.message-source}, then {@link CopilotAgent#messageSource()};</li>
 * <li>otherwise the field is omitted and the runtime treats the message as user input.</li>
 * </ol>
 * Inference never labels a message as {@code user}; only explicit configuration or a scope can.
 *
 * <p><b>Security:</b> the source is caller-supplied provenance, not an authenticated identity. Never make
 * authorization decisions from it. The runtime may use it for auto-approval classification, queueing, steering, and
 * routing, and a non-user source is persisted in session history. {@code system} labels application-generated
 * context; it is not a system-role message and does not change the system prompt. Agent identifiers are validated
 * against {@value #AGENT_ID_PATTERN}; never put secrets or user-controlled text in them.
 */
public final class CopilotMessageSources {

    /** Accepted agent identifier syntax. */
    public static final String AGENT_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";

    private static final Pattern AGENT_ID = Pattern.compile(AGENT_ID_PATTERN);
    private static final String AGENT_PREFIX = "agent-";

    private CopilotMessageSources() {
    }

    /**
     * Returns the {@code agent-<id>} source after validating {@code id}.
     *
     * @throws IllegalArgumentException when {@code id} does not match {@value #AGENT_ID_PATTERN}
     */
    public static MessageSource agent(String id) {
        if (id == null || !AGENT_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid Copilot message source agent id; expected " + AGENT_ID_PATTERN);
        }
        return MessageSource.agent(id);
    }

    /**
     * Parses {@code user}, {@code system}, or {@code agent-<id>}; a null or empty value yields {@code null}
     * (runtime default).
     *
     * @throws IllegalArgumentException for any other value
     */
    public static MessageSource parse(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.equals(MessageSource.USER.getValue())) {
            return MessageSource.USER;
        }
        if (value.equals(MessageSource.SYSTEM.getValue())) {
            return MessageSource.SYSTEM;
        }
        if (value.startsWith(AGENT_PREFIX)) {
            return agent(value.substring(AGENT_PREFIX.length()));
        }
        throw new IllegalArgumentException(
                "Invalid Copilot message source; expected 'user', 'system', or 'agent-<id>'");
    }

    /**
     * Runs {@code action} with {@code source} labeling every agent invocation started on the current thread, for
     * example {@code runAs(MessageSource.SYSTEM, ...)} from a scheduled job. Scopes nest; the innermost wins.
     */
    public static void runAs(MessageSource source, Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action is required");
        }
        callAs(source, () -> {
            action.run();
            return null;
        });
    }

    /** Calls {@code action} with {@code source} labeling every agent invocation started on the current thread. */
    public static <T> T callAs(MessageSource source, Supplier<T> action) {
        if (action == null) {
            throw new IllegalArgumentException("action is required");
        }
        var previous = MessageSourceScope.swap(validated(source));
        try {
            return action.get();
        } finally {
            MessageSourceScope.swap(previous);
        }
    }

    /** The source of the innermost {@code runAs}/{@code callAs} scope on this thread, or {@code null}. */
    public static MessageSource current() {
        return MessageSourceScope.current();
    }

    private static MessageSource validated(MessageSource source) {
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        // Re-validates agent identifiers built with the unchecked SDK factory.
        return parse(source.getValue());
    }
}
