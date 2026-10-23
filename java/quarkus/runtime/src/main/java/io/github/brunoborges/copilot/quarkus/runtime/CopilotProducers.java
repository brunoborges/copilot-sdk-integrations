package io.github.brunoborges.copilot.quarkus.runtime;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;

import io.github.brunoborges.copilot.agent.CopilotAgentTelemetry;
import io.github.brunoborges.copilot.agent.CopilotAgents;
import io.github.brunoborges.copilot.agent.CopilotSessionStore;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.agent.internal.CopilotSessionStorage;
import io.github.brunoborges.copilot.agent.internal.DefaultCopilotAgents;
import io.github.brunoborges.copilot.agent.internal.DefaultCopilotSessionStore;
import io.github.brunoborges.copilot.quarkus.runtime.config.CopilotConfigs;
import io.github.brunoborges.copilot.quarkus.runtime.config.CopilotRuntimeConfig;
import io.quarkus.arc.DefaultBean;

/** Default Copilot beans; applications override any of them by declaring a bean of the same type. */
@Singleton
public class CopilotProducers {

    @Produces
    @Singleton
    @DefaultBean
    CopilotClientOptions copilotClientOptions(CopilotRuntimeConfig config) {
        return CopilotConfigs.clientOptions(config);
    }

    @Produces
    @Singleton
    @DefaultBean
    CopilotClient copilotClient(CopilotClientOptions options) {
        return new CopilotClient(options);
    }

    void closeClient(@Disposes CopilotClient client) {
        client.close();
    }

    @Produces
    @Singleton
    @DefaultBean
    CopilotAgentTelemetry copilotAgentTelemetry(Instance<CopilotMetricsTelemetry> metrics) {
        return metrics.isResolvable() ? metrics.get() : CopilotAgentTelemetry.NOOP;
    }

    @Produces
    @Singleton
    CopilotAgentInvoker copilotAgentInvoker(CopilotClient client, CopilotAgentTelemetry telemetry,
            @Any Instance<CopilotAgentTelemetryDecorator> decorators, CopilotRuntimeConfig config) {
        CopilotAgentTelemetry effective = telemetry;
        for (var decorator : decorators) {
            effective = decorator.apply(effective);
        }
        return new CopilotAgentInvoker(client, effective, CopilotConfigs.execution(config), new ArcContextPropagation());
    }

    void closeInvoker(@Disposes CopilotAgentInvoker invoker) {
        invoker.close();
    }

    @Produces
    @Singleton
    @DefaultBean
    CopilotAgents copilotAgents(CopilotAgentRegistry registry) {
        return new DefaultCopilotAgents(registry::factory);
    }

    @Produces
    @Singleton
    @DefaultBean
    CopilotSessionStore copilotSessionStore(CopilotClient client, CopilotAgentInvoker invoker,
            CopilotAgentRegistry registry, CopilotRuntimeConfig config) {
        var timeout = config.sessions().timeout();
        CopilotConfigs.requirePositive(timeout, "quarkus.copilot.sessions.timeout");
        return new DefaultCopilotSessionStore(CopilotSessionStorage.of(client), invoker, type -> {
            var factory = registry.factory(type);
            return factory == null ? null : factory.definition();
        }, timeout);
    }
}
