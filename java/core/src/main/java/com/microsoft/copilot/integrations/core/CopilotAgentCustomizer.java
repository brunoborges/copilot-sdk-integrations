package com.microsoft.copilot.integrations.core;

import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;

/** Override both methods when settings must be reapplied on create and resume. */
public interface CopilotAgentCustomizer {
    default void customize(CopilotAgentContext context, SessionConfig config) {
    }

    default void customize(CopilotAgentContext context, ResumeSessionConfig config) {
    }
}
