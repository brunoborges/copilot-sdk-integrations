package com.microsoft.copilot.integrations.quarkus.runtime;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;

import com.microsoft.copilot.integrations.core.CopilotAgentTelemetry;
import com.microsoft.copilot.integrations.core.CopilotAgents;
import com.microsoft.copilot.integrations.core.CopilotLoops;
import com.microsoft.copilot.integrations.core.CopilotSessionStore;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentInvoker;
import com.microsoft.copilot.integrations.core.internal.CopilotSessionStorage;
import com.microsoft.copilot.integrations.core.internal.DefaultCopilotAgents;
import com.microsoft.copilot.integrations.core.internal.DefaultCopilotLoops;
import com.microsoft.copilot.integrations.core.internal.DefaultCopilotSessionStore;
import com.microsoft.copilot.integrations.quarkus.runtime.config.CopilotConfigs;
import com.microsoft.copilot.integrations.quarkus.runtime.config.CopilotRuntimeConfig;
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
    CopilotLoops copilotLoops(CopilotAgents agents) {
        return new DefaultCopilotLoops(agents);
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
