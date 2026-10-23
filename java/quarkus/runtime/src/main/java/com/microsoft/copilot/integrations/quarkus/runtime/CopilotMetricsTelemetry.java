package com.microsoft.copilot.integrations.quarkus.runtime;

import com.microsoft.copilot.integrations.core.CopilotAgentTelemetry;

/** Telemetry contributed by a metrics integration; used when the application declares no telemetry bean. */
public interface CopilotMetricsTelemetry extends CopilotAgentTelemetry {
}
