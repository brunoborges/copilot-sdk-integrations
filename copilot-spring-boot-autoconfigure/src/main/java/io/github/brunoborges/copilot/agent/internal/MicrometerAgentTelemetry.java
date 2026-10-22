package io.github.brunoborges.copilot.agent.internal;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import com.github.copilot.generated.AssistantUsageEvent;
import io.github.brunoborges.copilot.agent.CopilotAgentTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

public final class MicrometerAgentTelemetry implements CopilotAgentTelemetry {

    private static final Logger log = Logger.getLogger(MicrometerAgentTelemetry.class.getName());
    private final ObservationRegistry registry;
    private final MeterRegistry meters;

    public MicrometerAgentTelemetry(ObservationRegistry registry, MeterRegistry meters) {
        this.registry = registry;
        this.meters = meters;
    }

    @Override
    public Call start(String agent, String model) {
        String requestedModel = model.isEmpty() ? "runtime-default" : model;
        return new ObservedCall(agent, requestedModel, Observation.createNotStarted("copilot.agent.invocation", registry)
                .lowCardinalityKeyValue("agent", agent).lowCardinalityKeyValue("model", requestedModel).start());
    }

    @Override
    public void rejected(String agent) {
        if (meters != null) {
            meters.counter("copilot.agent.rejections", "agent", agent).increment();
        }
    }

    private final class ObservedCall implements Call {
        private final String agent;
        private final String model;
        private final Observation observation;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final Set<UUID> usageEvents = ConcurrentHashMap.newKeySet();
        private volatile String outcome = "success";

        private ObservedCall(String agent, String model, Observation observation) {
            this.agent = agent;
            this.model = model;
            this.observation = observation;
        }

        @Override
        public Scope openScope() {
            var scope = observation.openScope();
            return scope::close;
        }

        @Override
        public Call tool(String name) {
            return new ObservedCall(agent, model, Observation.createNotStarted("copilot.agent.tool", registry)
                    .parentObservation(observation).lowCardinalityKeyValue("agent", agent)
                    .lowCardinalityKeyValue("tool", name).start());
        }

        @Override
        public void usage(AssistantUsageEvent event) {
            if (meters == null || event.getData() == null
                    || (event.getId() != null && !usageEvents.add(event.getId()))) {
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
            observation.error(cause);
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
                observation.lowCardinalityKeyValue("outcome", outcome).stop();
            }
        }
    }
}
