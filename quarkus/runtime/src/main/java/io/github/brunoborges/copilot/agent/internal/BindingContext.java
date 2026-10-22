package io.github.brunoborges.copilot.agent.internal;

import java.util.Map;

import io.github.brunoborges.copilot.quarkus.runtime.config.CopilotAgentsProperties;
import io.github.brunoborges.copilot.quarkus.runtime.config.CopilotMcpProperties;
import io.github.brunoborges.copilot.quarkus.runtime.config.CopilotProviderProperties;

/** Everything needed to resolve an agent definition: beans, placeholders, resources, and configuration. */
public record BindingContext(BeanLookup beans, PropertyResolver environment, ResourceLookup resources,
        CopilotAgentsProperties agents, CopilotMcpProperties mcp, CopilotProviderProperties providers) {

    public BindingContext {
        Assert.notNull(beans, "BeanLookup is required");
        environment = environment == null ? PropertyResolver.NONE : environment;
        resources = resources == null ? ResourceLookup.classpath(null) : resources;
        agents = agents == null ? new CopilotAgentsProperties(Map.of()) : agents;
        mcp = mcp == null ? new CopilotMcpProperties(Map.of()) : mcp;
        providers = providers == null ? new CopilotProviderProperties(Map.of()) : providers;
    }
}
