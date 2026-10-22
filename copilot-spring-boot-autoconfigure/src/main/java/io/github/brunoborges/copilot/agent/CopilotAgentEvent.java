package io.github.brunoborges.copilot.agent;

import com.github.copilot.generated.SessionEvent;

public record CopilotAgentEvent(CopilotAgentContext context, SessionEvent event) {
}
