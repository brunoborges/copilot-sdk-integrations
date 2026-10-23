package io.github.brunoborges.copilot.micronaut;

import java.util.List;

import jakarta.inject.Singleton;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;

import io.github.brunoborges.copilot.agent.CopilotAgentTelemetry;
import io.github.brunoborges.copilot.agent.CopilotAgents;
import io.github.brunoborges.copilot.agent.CopilotLoops;
import io.github.brunoborges.copilot.agent.CopilotSessionStore;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.agent.internal.CopilotSessionStorage;
import io.github.brunoborges.copilot.agent.internal.DefaultCopilotAgents;
import io.github.brunoborges.copilot.agent.internal.DefaultCopilotLoops;
import io.github.brunoborges.copilot.agent.internal.DefaultCopilotSessionStore;
import io.github.brunoborges.copilot.micronaut.config.CopilotConfigs;
import io.github.brunoborges.copilot.micronaut.config.CopilotRuntimeConfig;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Secondary;
import io.micronaut.context.env.Environment;

/**
 * Default Copilot beans. They are {@link Secondary}, so an application bean of the same type takes precedence
 * without further configuration; {@code @Replaces} removes a default entirely.
 */
@Factory
@Requires(property = CopilotRuntimeConfig.PREFIX + ".enabled", notEquals = "false")
public class CopilotFactory {

    @Singleton
    CopilotRuntimeConfig copilotRuntimeConfig(Environment environment) {
        return CopilotRuntimeConfig.from(environment);
    }

    @Singleton
    @Secondary
    CopilotClientOptions copilotClientOptions(CopilotRuntimeConfig config) {
        return CopilotConfigs.clientOptions(config);
    }

    @Singleton
    @Secondary
    @Bean(preDestroy = "close")
    CopilotClient copilotClient(CopilotClientOptions options) {
        return new CopilotClient(options);
    }

    @Singleton
    @Secondary
    CopilotAgentTelemetry copilotAgentTelemetry(BeanProvider<CopilotMetricsTelemetry> metrics) {
        return metrics.isUnique() ? metrics.get() : CopilotAgentTelemetry.NOOP;
    }

    @Singleton
    @Bean(preDestroy = "close")
    CopilotAgentInvoker copilotAgentInvoker(CopilotClient client, CopilotAgentTelemetry telemetry,
            List<CopilotAgentTelemetryDecorator> decorators, CopilotRuntimeConfig config) {
        CopilotAgentTelemetry effective = telemetry;
        for (var decorator : decorators) {
            effective = decorator.apply(effective);
        }
        return new CopilotAgentInvoker(client, effective, CopilotConfigs.execution(config),
                new MicronautContextPropagation());
    }

    @Singleton
    @Secondary
    CopilotAgents copilotAgents(CopilotAgentRegistry registry) {
        return new DefaultCopilotAgents(registry::factory);
    }

    @Singleton
    @Secondary
    CopilotLoops copilotLoops(CopilotAgents agents) {
        return new DefaultCopilotLoops(agents);
    }

    @Singleton
    @Secondary
    CopilotSessionStore copilotSessionStore(CopilotClient client, CopilotAgentInvoker invoker,
            CopilotAgentRegistry registry, CopilotRuntimeConfig config) {
        var timeout = config.sessions().timeout();
        CopilotConfigs.requirePositive(timeout, CopilotRuntimeConfig.PREFIX + ".sessions.timeout");
        return new DefaultCopilotSessionStore(CopilotSessionStorage.of(client), invoker, type -> {
            var factory = registry.factory(type);
            return factory == null ? null : factory.definition();
        }, timeout);
    }
}
