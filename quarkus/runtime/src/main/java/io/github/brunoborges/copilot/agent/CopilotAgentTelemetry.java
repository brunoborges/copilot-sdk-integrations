package io.github.brunoborges.copilot.agent;

import com.github.copilot.generated.AssistantUsageEvent;

/** Optional instrumentation SPI; implementations must not record prompts or conversation keys as metric tags. */
public interface CopilotAgentTelemetry {

    CopilotAgentTelemetry NOOP = (agent, model) -> Call.NOOP;

    Call start(String agent, String model);

    default void rejected(String agent) {
    }

    interface Call {
        Call NOOP = new Call() {
        };

        default Scope openScope() {
            return () -> { };
        }

        default Call tool(String name) {
            return NOOP;
        }

        default void usage(AssistantUsageEvent event) {
        }

        default void error(Throwable error) {
        }

        default void cancelled() {
        }

        default void stop() {
        }
    }

    @FunctionalInterface
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
