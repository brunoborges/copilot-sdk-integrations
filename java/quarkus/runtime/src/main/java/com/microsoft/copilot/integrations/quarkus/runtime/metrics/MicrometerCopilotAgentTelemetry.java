package com.microsoft.copilot.integrations.quarkus.runtime.metrics;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import com.github.copilot.generated.AssistantUsageEvent;

import com.microsoft.copilot.integrations.quarkus.runtime.CopilotMetricsTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Records agent invocations and tool calls as Micrometer timers ({@code copilot.agent.invocation},
 * {@code copilot.agent.tool}), token usage ({@code copilot.agent.tokens}), and admission rejections
 * ({@code copilot.agent.rejections}). Prompts and conversation IDs are never used as tags.
 */
@Singleton
@Typed(CopilotMetricsTelemetry.class)
public class MicrometerCopilotAgentTelemetry implements CopilotMetricsTelemetry {

    private static final Logger log = Logger.getLogger(MicrometerCopilotAgentTelemetry.class.getName());

    private final MeterRegistry meters;

    @Inject
    public MicrometerCopilotAgentTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public Call start(String agent, String model) {
        String requestedModel = model == null || model.isEmpty() ? "runtime-default" : model;
        return new TimedCall("copilot.agent.invocation", agent, requestedModel, null);
    }

    @Override
    public void rejected(String agent) {
        meters.counter("copilot.agent.rejections", "agent", agent).increment();
    }

    private final class TimedCall implements Call {
        private final String name;
        private final String agent;
        private final String model;
        private final String tool;
        private final Timer.Sample sample;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final Set<UUID> usageEvents = ConcurrentHashMap.newKeySet();
        private volatile String outcome = "success";

        private TimedCall(String name, String agent, String model, String tool) {
            this.name = name;
            this.agent = agent;
            this.model = model;
            this.tool = tool;
            this.sample = Timer.start(meters);
        }

        @Override
        public Call tool(String toolName) {
            return new TimedCall("copilot.agent.tool", agent, model, toolName);
        }

        @Override
        public void usage(AssistantUsageEvent event) {
            if (event.getData() == null || (event.getId() != null && !usageEvents.add(event.getId()))) {
                return;
            }
            var data = event.getData();
            String actualModel = data.model() == null ? model : data.model();
            tokens(actualModel, "input", data.inputTokens());
            tokens(actualModel, "output", data.outputTokens());
            tokens(actualModel, "cache-read", data.cacheReadTokens());
            tokens(actualModel, "cache-write", data.cacheWriteTokens());
        }

        private void tokens(String actualModel, String kind, Long count) {
            if (count != null && count < 0) {
                log.warning("Ignoring a negative SDK token usage count for agent '" + agent + "'.");
            } else if (count != null && count > 0) {
                meters.counter("copilot.agent.tokens", "agent", agent, "model", actualModel, "kind", kind).increment(count);
            }
        }

        @Override
        public void error(Throwable error) {
            Throwable cause = error;
            while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
                cause = cause.getCause();
            }
            outcome = cause instanceof TimeoutException ? "timeout" : "error";
        }

        @Override
        public void cancelled() {
            if (outcome.equals("success")) {
                outcome = "cancelled";
            }
        }

        @Override
        public void stop() {
            if (stopped.compareAndSet(false, true)) {
                var builder = Timer.builder(name).tag("agent", agent).tag("outcome", outcome);
                builder = tool == null ? builder.tag("model", model) : builder.tag("tool", tool);
                sample.stop(builder.register(meters));
            }
        }
    }
}
