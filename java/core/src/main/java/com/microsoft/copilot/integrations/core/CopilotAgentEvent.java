package com.microsoft.copilot.integrations.core;

import com.github.copilot.generated.SessionEvent;

/**
 * A native SDK session event paired with the invocation that observed it.
 *
 * @param context the agent, conversation, and session identity
 * @param event the unmodified SDK event
 */
public record CopilotAgentEvent(CopilotAgentContext context, SessionEvent event) {
}
