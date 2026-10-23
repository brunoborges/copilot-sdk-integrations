package com.microsoft.copilot.integrations.core.internal;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.copilot.rpc.AgentStopHookOutput;
import com.github.copilot.rpc.PostToolUseFailureHookOutput;
import com.github.copilot.rpc.PostToolUseHookOutput;
import com.github.copilot.rpc.PreToolUseHookOutput;
import com.github.copilot.rpc.SessionEndHookOutput;
import com.github.copilot.rpc.SessionHooks;
import com.github.copilot.rpc.SessionStartHookOutput;
import com.github.copilot.rpc.SubagentStartHookOutput;
import com.github.copilot.rpc.SubagentStopHookOutput;
import com.github.copilot.rpc.UserPromptSubmittedHookInput;
import com.github.copilot.rpc.UserPromptSubmittedHookOutput;
import com.microsoft.copilot.integrations.core.CopilotAgentContext;
import com.microsoft.copilot.integrations.core.CopilotAgentHooks;

/** Adapts ordered {@link CopilotAgentHooks} beans to one SDK {@link SessionHooks} configuration. */
final class CopilotAgentHookChain {

    private static final Logger log = Logger.getLogger(CopilotAgentHookChain.class.getName());
    private static final String DENY = "deny";
    private static final String ASK = "ask";
    private static final String BLOCK = "block";

    private CopilotAgentHookChain() {
    }

    static SessionHooks sessionHooks(List<CopilotAgentHooks> hooks, CopilotAgentContext context,
            Supplier<CopilotAgentLifetime.Scope> owner) {
        return new SessionHooks()
                .setOnPreToolUse((input, invocation) -> done(() -> {
                    String decision = null;
                    String reason = null;
                    JsonNode modifiedArgs = null;
                    var additional = new Context();
                    for (var hook : hooks) {
                        PreToolUseHookOutput output;
                        try (var scope = owner.get()) {
                            output = hook.onPreToolUse(context, input);
                        } catch (RuntimeException ex) {
                            log.log(Level.SEVERE, "Copilot agent '" + context.agentName() + "' pre-tool-use hook failed; "
                                    + "denying tool '" + input.getToolName() + "'", ex);
                            return new PreToolUseHookOutput(DENY, "A pre-tool-use hook failed", null,
                                    additional.value(), additional.suppress());
                        }
                        if (output == null) {
                            continue;
                        }
                        additional.add(output.additionalContext(), output.suppressOutput());
                        if (output.modifiedArgs() != null) {
                            modifiedArgs = output.modifiedArgs();
                            input.setToolArgs(modifiedArgs);
                        }
                        if (DENY.equals(output.permissionDecision())) {
                            return new PreToolUseHookOutput(DENY, output.permissionDecisionReason(), null,
                                    additional.value(), additional.suppress());
                        }
                        if (output.permissionDecision() != null && !ASK.equals(decision)) {
                            decision = output.permissionDecision();
                            reason = output.permissionDecisionReason();
                        }
                    }
                    if (decision == null && modifiedArgs == null && additional.empty()) {
                        return null;
                    }
                    return new PreToolUseHookOutput(decision, reason, modifiedArgs, additional.value(), additional.suppress());
                }, () -> PreToolUseHookOutput.deny("Pre-tool-use hooks failed")))
                .setOnPostToolUse((input, invocation) -> done(() -> {
                    JsonNode modifiedResult = null;
                    var additional = new Context();
                    for (var hook : hooks) {
                        var output = observe(context, "post-tool-use", () -> hook.onPostToolUse(context, input), owner);
                        if (output == null) {
                            continue;
                        }
                        additional.add(output.additionalContext(), output.suppressOutput());
                        if (output.modifiedResult() != null) {
                            modifiedResult = output.modifiedResult();
                            input.setToolResult(modifiedResult);
                        }
                    }
                    return modifiedResult == null && additional.empty() ? null
                            : new PostToolUseHookOutput(modifiedResult, additional.value(), additional.suppress());
                }))
                .setOnPostToolUseFailure((input, invocation) -> done(() -> {
                    var additional = new Context();
                    for (var hook : hooks) {
                        var output = observe(context, "post-tool-use-failure",
                                () -> hook.onPostToolUseFailure(context, input), owner);
                        if (output != null) {
                            additional.add(output.getAdditionalContext(), null);
                        }
                    }
                    return additional.empty() ? null
                            : new PostToolUseFailureHookOutput().setAdditionalContext(additional.value());
                }))
                .setOnUserPromptSubmitted((input, invocation) -> done(() -> {
                    var current = input;
                    String modifiedPrompt = null;
                    var additional = new Context();
                    for (var hook : hooks) {
                        UserPromptSubmittedHookOutput output;
                        var submitted = current;
                        try (var scope = owner.get()) {
                            output = hook.onUserPromptSubmitted(context, submitted);
                        } catch (RuntimeException ex) {
                            log.log(Level.SEVERE, "Copilot agent '" + context.agentName()
                                    + "' user-prompt-submitted hook failed; blocking the prompt", ex);
                            return UserPromptSubmittedHookOutput.block("A user-prompt-submitted hook failed");
                        }
                        if (output == null) {
                            continue;
                        }
                        if (BLOCK.equals(output.decision())) {
                            return output;
                        }
                        additional.add(output.additionalContext(), output.suppressOutput());
                        if (output.modifiedPrompt() != null) {
                            modifiedPrompt = output.modifiedPrompt();
                            current = new UserPromptSubmittedHookInput(current.sessionId(), current.timestamp(),
                                    current.cwd(), modifiedPrompt, current.agentId(), current.agentType(),
                                    current.parentAgentId());
                        }
                    }
                    return modifiedPrompt == null && additional.empty() ? null
                            : new UserPromptSubmittedHookOutput(modifiedPrompt, additional.value(), additional.suppress());
                }, () -> UserPromptSubmittedHookOutput.block("User-prompt-submitted hooks failed")))
                .setOnSessionStart((input, invocation) -> done(() -> {
                    var additional = new Context();
                    Map<String, Object> modifiedConfig = null;
                    for (var hook : hooks) {
                        var output = observe(context, "session-start", () -> hook.onSessionStart(context, input), owner);
                        if (output != null) {
                            additional.add(output.additionalContext(), null);
                            modifiedConfig = output.modifiedConfig() == null ? modifiedConfig : output.modifiedConfig();
                        }
                    }
                    return additional.empty() && modifiedConfig == null ? null
                            : new SessionStartHookOutput(additional.value(), modifiedConfig);
                }))
                .setOnSessionEnd((input, invocation) -> done(() -> {
                    SessionEndHookOutput merged = null;
                    for (var hook : hooks) {
                        var output = observe(context, "session-end", () -> hook.onSessionEnd(context, input), owner);
                        if (output != null) {
                            merged = merged == null ? output : new SessionEndHookOutput(
                                    Boolean.TRUE.equals(merged.suppressOutput()) || Boolean.TRUE.equals(output.suppressOutput())
                                            ? Boolean.TRUE : output.suppressOutput() != null ? output.suppressOutput()
                                            : merged.suppressOutput(),
                                    output.cleanupActions() != null ? output.cleanupActions() : merged.cleanupActions(),
                                    output.sessionSummary() != null ? output.sessionSummary() : merged.sessionSummary());
                        }
                    }
                    return merged;
                }))
                .setOnAgentStop((input, invocation) -> done(() -> {
                    AgentStopHookOutput last = null;
                    for (var hook : hooks) {
                        var output = observe(context, "agent-stop", () -> hook.onAgentStop(context, input), owner);
                        if (output != null) {
                            if (BLOCK.equals(output.getDecision())) {
                                return output;
                            }
                            last = output;
                        }
                    }
                    return last;
                }))
                .setOnSubagentStart((input, invocation) -> done(() -> {
                    var additional = new Context();
                    for (var hook : hooks) {
                        var output = observe(context, "subagent-start", () -> hook.onSubagentStart(context, input), owner);
                        if (output != null) {
                            additional.add(output.additionalContext(), null);
                        }
                    }
                    return additional.empty() ? null : new SubagentStartHookOutput(additional.value());
                }))
                .setOnSubagentStop((input, invocation) -> done(() -> {
                    SubagentStopHookOutput last = null;
                    String modifiedResponse = null;
                    for (var hook : hooks) {
                        var output = observe(context, "subagent-stop", () -> hook.onSubagentStop(context, input), owner);
                        if (output != null) {
                            if (BLOCK.equals(output.decision())) {
                                return output;
                            }
                            modifiedResponse = output.modifiedResponse() != null ? output.modifiedResponse() : modifiedResponse;
                            last = output;
                        }
                    }
                    return last == null ? null : new SubagentStopHookOutput(last.decision(), last.reason(), modifiedResponse);
                }));
    }

    private static <T> CompletableFuture<T> done(Supplier<T> chain) {
        try {
            return CompletableFuture.completedFuture(chain.get());
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    // Gate hooks fail closed: an unexpected failure must not let the runtime fall back to its default decision.
    private static <T> CompletableFuture<T> done(Supplier<T> chain, Supplier<T> failClosed) {
        try {
            return CompletableFuture.completedFuture(chain.get());
        } catch (RuntimeException | Error ex) {
            log.log(Level.SEVERE, "Copilot agent hook chain failed; failing closed", ex);
            return CompletableFuture.completedFuture(failClosed.get());
        }
    }

    private static <T> T observe(CopilotAgentContext context, String hook, Supplier<T> call,
            Supplier<CopilotAgentLifetime.Scope> owner) {
        try (var scope = owner.get()) {
            return call.get();
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "Copilot agent '" + context.agentName() + "' " + hook + " hook failed; ignoring it", ex);
            return null;
        }
    }

    private static final class Context {
        private final StringBuilder text = new StringBuilder();
        private Boolean suppress;

        void add(String additionalContext, Boolean suppressOutput) {
            if (additionalContext != null && !additionalContext.isEmpty()) {
                if (!text.isEmpty()) {
                    text.append("\n\n");
                }
                text.append(additionalContext);
            }
            if (suppressOutput != null) {
                suppress = Boolean.TRUE.equals(suppress) || suppressOutput;
            }
        }

        boolean empty() {
            return text.isEmpty() && suppress == null;
        }

        String value() {
            return text.isEmpty() ? null : text.toString();
        }

        Boolean suppress() {
            return suppress;
        }
    }
}
