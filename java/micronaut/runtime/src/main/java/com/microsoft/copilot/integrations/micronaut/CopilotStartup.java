package com.microsoft.copilot.integrations.micronaut;

import jakarta.inject.Singleton;

import com.github.copilot.CopilotClient;

import com.microsoft.copilot.integrations.micronaut.config.CopilotRuntimeConfig;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;

/** Validates every agent at startup and, when {@code micronaut.copilot.eager-start} is set, connects the client. */
@Singleton
@Requires(property = CopilotRuntimeConfig.PREFIX + ".enabled", notEquals = "false")
final class CopilotStartup implements ApplicationEventListener<StartupEvent> {

    private final CopilotAgentRegistry registry;
    private final CopilotRuntimeConfig config;
    private final BeanProvider<CopilotClient> client;

    CopilotStartup(CopilotAgentRegistry registry, CopilotRuntimeConfig config, BeanProvider<CopilotClient> client) {
        this.registry = registry;
        this.config = config;
        this.client = client;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        registry.all();
        if (config.eagerStart()) {
            CopilotClientLifecycle.start(client.get(), config.startupTimeout());
        }
    }
}
