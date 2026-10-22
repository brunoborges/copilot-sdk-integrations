package io.github.brunoborges.copilot.agent;

import java.lang.reflect.Type;

public interface CopilotAgentReturnTypeAdapter {
    boolean supports(Type returnType);
    Object adapt(CopilotAgentInvocation invocation);
}
