package com.microsoft.copilot.integrations.core;

import com.github.copilot.generated.SessionEvent;

@FunctionalInterface
public interface CopilotAgentListener {
    /** Observe SDK events without blocking or waiting for another invocation on the same conversation. */
    void onEvent(CopilotAgentContext context, SessionEvent event);
}
