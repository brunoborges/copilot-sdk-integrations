package com.microsoft.copilot.integrations.core;

import com.microsoft.copilot.rpc.ResumeSessionOptions;
import com.microsoft.copilot.rpc.CreateSessionOptions;

/** Override both methods when settings must be reapplied on create and resume. */
public interface CopilotAgentCustomizer {
    default void customize(CopilotAgentContext context, CreateSessionOptions config) {
    }

    default void customize(CopilotAgentContext context, ResumeSessionOptions config) {
    }
}
