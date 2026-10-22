package io.github.brunoborges.copilot.agent;

import java.lang.reflect.Type;

/**
 * Extension SPI that maps an agent method's return type onto a streaming invocation. Register implementations
 * as CDI beans; at most one adapter may support a given return type.
 */
public interface CopilotAgentReturnTypeAdapter {

    /** Whether this adapter handles the generic return type of an agent method. */
    boolean supports(Type returnType);

    /**
     * Adapts an invocation to the method's return value. Implementations should defer {@code invocation.invoke}
     * until the caller requests results, and invoke it at most once per logical request.
     */
    Object adapt(CopilotAgentInvocation invocation);
}
