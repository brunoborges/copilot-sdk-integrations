package com.microsoft.copilot.integrations.quarkus.runtime;

import java.util.function.UnaryOperator;

import com.microsoft.copilot.integrations.core.CopilotAgentTelemetry;

/** Beans of this type wrap the effective {@link CopilotAgentTelemetry}, for example to record test assertions. */
@FunctionalInterface
public interface CopilotAgentTelemetryDecorator extends UnaryOperator<CopilotAgentTelemetry> {
}
