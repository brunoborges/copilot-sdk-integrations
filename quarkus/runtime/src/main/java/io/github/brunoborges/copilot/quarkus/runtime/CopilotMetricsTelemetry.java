package io.github.brunoborges.copilot.quarkus.runtime;

import io.github.brunoborges.copilot.agent.CopilotAgentTelemetry;

/** Telemetry contributed by a metrics integration; used when the application declares no telemetry bean. */
public interface CopilotMetricsTelemetry extends CopilotAgentTelemetry {
}
