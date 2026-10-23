package com.microsoft.copilot.integrations.core;

import java.lang.System.Logger.Level;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.copilot.rpc.PostToolUseFailureHookInput;
import com.github.copilot.rpc.PostToolUseFailureHookOutput;
import com.github.copilot.rpc.PostToolUseHookInput;
import com.github.copilot.rpc.PostToolUseHookOutput;
import com.github.copilot.rpc.SessionEndHookInput;
import com.github.copilot.rpc.SessionEndHookOutput;

/**
 * Opt-in hooks that keep an agent working when tools fail, instead of stopping with a partial answer.
 *
 * <p>Tool results and tool failures are sorted into {@link Category categories} by case-insensitive phrase lists.
 * For a permission denial or an error, the hook adds a note to the model's context (via {@code additionalContext},
 * the tool result itself is unchanged) telling it to retry differently, use another tool, or move on. After
 * {@link Builder#maxConsecutiveFailures(int) N} consecutive failures of the same tool in a session the hook stops
 * adding notes, so the model may give up rather than loop. A successful result of the tool resets its count.
 *
 * <p>Nothing is enabled by default. Register an instance as a hook bean, for example with
 * {@link GlobalCopilotAgentHooks} on a bean factory method, or reference it from {@link CopilotAgent#hookRefs()}.
 *
 * <p>The Python and Node.js versions also retry transient SDK errors (timeouts, rate limits, 5xx) from the
 * {@code errorOccurred} hook. The Java SDK does not expose that hook, so this class only covers tool failures.
 *
 * <p>Instances are thread-safe and may be shared by every agent.
 */
public class CopilotErrorRecoveryHooks implements CopilotAgentHooks {

    /** How a tool result or failure was classified. */
    public enum Category {
        /** The tool was denied access, for example {@code permission denied} or {@code 403 forbidden}. */
        PERMISSION_DENIED,
        /** The tool ran a command or file operation that failed. */
        SHELL_ERROR,
        /** The tool failed with an error that matched no phrase list. */
        TOOL_FAILURE,
        /** The result looks successful. */
        NORMAL
    }

    /** Default phrases that mark a permission denial. */
    public static final List<String> DEFAULT_PERMISSION_PHRASES = List.of("permission denied", "access denied",
            "not permitted", "operation not allowed", "eacces", "eperm", "403 forbidden");

    /**
     * Default phrases that mark a shell or file error. {@code exit code} from the cookbook recipe is left out
     * because successful shell output often reports {@code exit code 0}.
     */
    public static final List<String> DEFAULT_SHELL_ERROR_PHRASES = List.of("command not found",
            "no such file or directory", "errno", "traceback", "non-zero exit");

    /** Default note for {@link Category#PERMISSION_DENIED}. */
    public static final String DEFAULT_PERMISSION_NOTE = "[SYSTEM NOTE: Permission was denied for this specific "
            + "action. Continue using alternative approaches.]";

    /** Default note for {@link Category#SHELL_ERROR} and {@link Category#TOOL_FAILURE}. */
    public static final String DEFAULT_ERROR_NOTE = "[SYSTEM NOTE: This command encountered an error. This does NOT "
            + "mean you should stop. Retry with different arguments, try a different tool, or move on.]";

    /** Default number of consecutive failures of one tool that still get a note. */
    public static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 3;

    private static final System.Logger log = System.getLogger(CopilotErrorRecoveryHooks.class.getName());
    private static final int MAX_TRACKED = 10_000;

    private final List<String> permissionPhrases;
    private final List<String> shellErrorPhrases;
    private final String permissionNote;
    private final String errorNote;
    private final int maxConsecutiveFailures;
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();

    /** Creates hooks with the default phrase lists, notes, and cap. */
    public CopilotErrorRecoveryHooks() {
        this(builder());
    }

    /**
     * Creates hooks from a builder; subclasses use this to add an annotation such as {@link GlobalCopilotAgentHooks}
     * in containers that read annotations from the bean class.
     *
     * @param builder the configuration
     */
    protected CopilotErrorRecoveryHooks(Builder builder) {
        this.permissionPhrases = lower(builder.permissionPhrases);
        this.shellErrorPhrases = lower(builder.shellErrorPhrases);
        this.permissionNote = builder.permissionNote;
        this.errorNote = builder.errorNote;
        this.maxConsecutiveFailures = builder.maxConsecutiveFailures;
    }

    /** @return a builder initialized with the defaults */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @param text tool output or error text, may be {@code null}
     * @return {@link Category#PERMISSION_DENIED}, {@link Category#SHELL_ERROR}, or {@link Category#NORMAL}
     */
    public Category classify(String text) {
        if (text == null || text.isEmpty()) {
            return Category.NORMAL;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (permissionPhrases.stream().anyMatch(lower::contains)) {
            return Category.PERMISSION_DENIED;
        }
        if (shellErrorPhrases.stream().anyMatch(lower::contains)) {
            return Category.SHELL_ERROR;
        }
        return Category.NORMAL;
    }

    @Override
    public PostToolUseHookOutput onPostToolUse(CopilotAgentContext context, PostToolUseHookInput input) {
        String session = session(context, input.getSessionId());
        Category category = classify(text(input.getToolResult()));
        if (category == Category.NORMAL) {
            failures.remove(key(session, input.getToolName()));
            return null;
        }
        String note = note(session, input.getToolName(), category);
        return note == null ? null : new PostToolUseHookOutput(null, note, null);
    }

    @Override
    public PostToolUseFailureHookOutput onPostToolUseFailure(CopilotAgentContext context,
            PostToolUseFailureHookInput input) {
        Category category = classify(input.getError());
        String note = note(session(context, input.getSessionId()), input.getToolName(),
                category == Category.NORMAL ? Category.TOOL_FAILURE : category);
        return note == null ? null : new PostToolUseFailureHookOutput().setAdditionalContext(note);
    }

    @Override
    public SessionEndHookOutput onSessionEnd(CopilotAgentContext context, SessionEndHookInput input) {
        String prefix = session(context, input.sessionId()) + '\u0000';
        failures.keySet().removeIf(key -> key.startsWith(prefix));
        return null;
    }

    private String note(String session, String tool, Category category) {
        if (failures.size() >= MAX_TRACKED) {
            failures.clear();
        }
        int count = failures.merge(key(session, tool), 1, Integer::sum);
        if (maxConsecutiveFailures > 0 && count > maxConsecutiveFailures) {
            log.log(Level.DEBUG, () -> "Tool " + tool + " failed " + count + " times in a row (" + category
                    + "); no longer asking the model to continue");
            return null;
        }
        log.log(Level.DEBUG, () -> "Tool " + tool + " failed (" + category + ", attempt " + count + ")");
        return category == Category.PERMISSION_DENIED ? permissionNote : errorNote;
    }

    private static String session(CopilotAgentContext context, String fromInput) {
        if (fromInput != null) {
            return fromInput;
        }
        return context == null || context.sessionId() == null ? "" : context.sessionId();
    }

    private static String key(String session, String tool) {
        return session + '\u0000' + (tool == null ? "" : tool);
    }

    private static String text(JsonNode result) {
        if (result == null || result.isNull() || result.isMissingNode()) {
            return null;
        }
        if (result.isTextual()) {
            return result.asText();
        }
        JsonNode text = result.get("textResultForLlm");
        return text != null && text.isTextual() ? text.asText() : result.toString();
    }

    private static List<String> lower(Collection<String> phrases) {
        return phrases.stream().filter(Objects::nonNull).map(String::trim).filter(p -> !p.isEmpty())
                .map(p -> p.toLowerCase(Locale.ROOT)).toList();
    }

    /** Configuration for {@link CopilotErrorRecoveryHooks}. */
    public static final class Builder {

        private Collection<String> permissionPhrases = DEFAULT_PERMISSION_PHRASES;
        private Collection<String> shellErrorPhrases = DEFAULT_SHELL_ERROR_PHRASES;
        private String permissionNote = DEFAULT_PERMISSION_NOTE;
        private String errorNote = DEFAULT_ERROR_NOTE;
        private int maxConsecutiveFailures = DEFAULT_MAX_CONSECUTIVE_FAILURES;

        private Builder() {
        }

        /**
         * @param phrases case-insensitive phrases that mark a permission denial, replacing the defaults
         * @return this builder
         */
        public Builder permissionPhrases(Collection<String> phrases) {
            this.permissionPhrases = List.copyOf(Objects.requireNonNull(phrases, "phrases"));
            return this;
        }

        /**
         * @param phrases case-insensitive phrases that mark a shell or file error, replacing the defaults
         * @return this builder
         */
        public Builder shellErrorPhrases(Collection<String> phrases) {
            this.shellErrorPhrases = List.copyOf(Objects.requireNonNull(phrases, "phrases"));
            return this;
        }

        /**
         * @param note the note added after a permission denial
         * @return this builder
         */
        public Builder permissionNote(String note) {
            this.permissionNote = requireText(note);
            return this;
        }

        /**
         * @param note the note added after a shell error or tool failure
         * @return this builder
         */
        public Builder errorNote(String note) {
            this.errorNote = requireText(note);
            return this;
        }

        /**
         * @param max consecutive failures of one tool in a session that still get a note; {@code 0} for no limit
         * @return this builder
         */
        public Builder maxConsecutiveFailures(int max) {
            if (max < 0) {
                throw new IllegalArgumentException("maxConsecutiveFailures must not be negative");
            }
            this.maxConsecutiveFailures = max;
            return this;
        }

        /** @return the configured hooks */
        public CopilotErrorRecoveryHooks build() {
            return new CopilotErrorRecoveryHooks(this);
        }

        private static String requireText(String note) {
            if (note == null || note.isBlank()) {
                throw new IllegalArgumentException("note must not be blank");
            }
            return note;
        }
    }
}
