package io.github.brunoborges.copilot.quarkus.runtime;

import java.util.function.UnaryOperator;

import io.github.brunoborges.copilot.agent.CopilotAgentTelemetry;

/** Beans of this type wrap the effective {@link CopilotAgentTelemetry}, for example to record test assertions. */
@FunctionalInterface
public interface CopilotAgentTelemetryDecorator extends UnaryOperator<CopilotAgentTelemetry> {
}
