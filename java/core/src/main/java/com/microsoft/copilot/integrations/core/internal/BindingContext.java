package com.microsoft.copilot.integrations.core.internal;

import java.util.function.Function;

import com.github.copilot.rpc.McpServerConfig;
import com.github.copilot.rpc.ProviderConfig;

/**
 * Everything needed to resolve an agent definition: beans, placeholders, resources, and configuration. Each
 * integration adapts its bean container and configuration source to this context.
 *
 * @param beans bean container operations
 * @param environment placeholder resolution for annotation values
 * @param resources opens instruction and prompt resources
 * @param agents per-agent configuration overrides
 * @param mcpServers returns a fresh SDK configuration for a configured MCP server name, or {@code null}
 * @param providers returns a fresh SDK configuration for a configured provider name, or {@code null}
 * @param configPrefix configuration prefix named in error messages, such as {@code copilot}
 */
public record BindingContext(BeanLookup beans, PropertyResolver environment, ResourceLookup resources,
        CopilotAgentSettings agents, Function<String, McpServerConfig> mcpServers,
        Function<String, ProviderConfig> providers, String configPrefix) {

    public BindingContext {
        Assert.notNull(beans, "BeanLookup is required");
        environment = environment == null ? PropertyResolver.NONE : environment;
        resources = resources == null ? ResourceLookup.classpath(null) : resources;
        agents = agents == null ? new CopilotAgentSettings(null) : agents;
        mcpServers = mcpServers == null ? name -> null : mcpServers;
        providers = providers == null ? name -> null : providers;
        configPrefix = configPrefix == null || configPrefix.isBlank() ? "copilot" : configPrefix;
    }
}
