package com.microsoft.copilot.integrations.core;

import com.microsoft.copilot.rpc.AgentStopHookInput;
import com.microsoft.copilot.rpc.AgentStopHookOutput;
import com.microsoft.copilot.rpc.PostToolUseFailureHookInput;
import com.microsoft.copilot.rpc.PostToolUseFailureHookOutput;
import com.microsoft.copilot.rpc.PostToolUseHookInput;
import com.microsoft.copilot.rpc.PostToolUseHookOutput;
import com.microsoft.copilot.rpc.PreToolUseHookInput;
import com.microsoft.copilot.rpc.PreToolUseHookOutput;
import com.microsoft.copilot.rpc.SessionEndHookInput;
import com.microsoft.copilot.rpc.SessionEndHookOutput;
import com.microsoft.copilot.rpc.SessionStartHookInput;
import com.microsoft.copilot.rpc.SessionStartHookOutput;
import com.microsoft.copilot.rpc.SubagentStartHookInput;
import com.microsoft.copilot.rpc.SubagentStartHookOutput;
import com.microsoft.copilot.rpc.SubagentStopHookInput;
import com.microsoft.copilot.rpc.SubagentStopHookOutput;
import com.microsoft.copilot.rpc.UserPromptSubmittedHookInput;
import com.microsoft.copilot.rpc.UserPromptSubmittedHookOutput;

/**
 * Bean adapter for SDK session hooks. Reference beans from {@link CopilotAgent#hookRefs()}, target agents
 * from the bean with {@link CopilotAgentHooksFor}, or annotate them with {@link GlobalCopilotAgentHooks} to apply
 * them to every agent.
 *
 * <p>Every method is optional and returns {@code null} for "no opinion". Hooks run synchronously on the SDK
 * callback thread, global hooks first, then targeted hooks (each in {@code @Order}), then the agent's hook references in declaration order, and the
 * results are combined:
 * <ul>
 *   <li>{@link #onPreToolUse}: the first {@code deny} wins and stops the chain; otherwise {@code ask} beats
 *       {@code allow}. Modified arguments are passed to later hooks. A hook exception denies the tool call.</li>
 *   <li>{@link #onUserPromptSubmitted}: the first {@code block} wins and stops the chain; a modified prompt is passed
 *       to later hooks. A hook exception blocks the prompt.</li>
 *   <li>{@link #onPostToolUse}: a modified result is passed to later hooks.</li>
 *   <li>{@link #onAgentStop} and {@link #onSubagentStop}: the first {@code block} decision wins.</li>
 *   <li>Additional context from all hooks is joined with blank lines; {@code suppressOutput} is set when any hook
 *       sets it; for other values the last non-null value wins.</li>
 * </ul>
 * Exceptions from other hooks are logged and ignored so observability hooks cannot break a conversation.
 */
public interface CopilotAgentHooks {

    /**
     * Called before any tool runs, including built-in, MCP, and application tools.
     *
     * @param context the agent invocation
     * @param input the SDK hook input with tool name and arguments
     * @return an allow, ask, or deny decision, or {@code null}
     */
    default PreToolUseHookOutput onPreToolUse(CopilotAgentContext context, PreToolUseHookInput input) {
        return null;
    }

    /**
     * @param context the agent invocation
     * @param input the SDK hook input with tool name, arguments, and result
     * @return a modified result or additional context, or {@code null}
     */
    default PostToolUseHookOutput onPostToolUse(CopilotAgentContext context, PostToolUseHookInput input) {
        return null;
    }

    /**
     * @param context the agent invocation
     * @param input the SDK hook input with tool name, arguments, and error
     * @return additional context, or {@code null}
     */
    default PostToolUseFailureHookOutput onPostToolUseFailure(CopilotAgentContext context,
            PostToolUseFailureHookInput input) {
        return null;
    }

    /**
     * @param context the agent invocation
     * @param input the SDK hook input with the submitted prompt
     * @return a modified prompt, a block decision, or {@code null}
     */
    default UserPromptSubmittedHookOutput onUserPromptSubmitted(CopilotAgentContext context,
            UserPromptSubmittedHookInput input) {
        return null;
    }

    /**
     * @param context the agent invocation
     * @param input the SDK hook input
     * @return additional context, or {@code null}
     */
    default SessionStartHookOutput onSessionStart(CopilotAgentContext context, SessionStartHookInput input) {
        return null;
    }

    /**
     * @param context the agent invocation
     * @param input the SDK hook input
     * @return a session summary, or {@code null}
     */
    default SessionEndHookOutput onSessionEnd(CopilotAgentContext context, SessionEndHookInput input) {
        return null;
    }

    /**
     * @param context the agent invocation
     * @param input the SDK hook input
     * @return a {@code block} decision to keep the agent running, or {@code null}
     */
    default AgentStopHookOutput onAgentStop(CopilotAgentContext context, AgentStopHookInput input) {
        return null;
    }

    /**
     * @param context the agent invocation
     * @param input the SDK hook input
     * @return additional context for the subagent, or {@code null}
     */
    default SubagentStartHookOutput onSubagentStart(CopilotAgentContext context, SubagentStartHookInput input) {
        return null;
    }

    /**
     * @param context the agent invocation
     * @param input the SDK hook input
     * @return a {@code block} decision, a modified response, or {@code null}
     */
    default SubagentStopHookOutput onSubagentStop(CopilotAgentContext context, SubagentStopHookInput input) {
        return null;
    }
}
