package io.github.brunoborges.copilot.agent.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.copilot.rpc.McpServerConfig;
import io.github.brunoborges.copilot.quarkus.runtime.config.CopilotMcpProperties;

final class McpServerBindings {

    private McpServerBindings() {
    }

    static Map<String, McpServerConfig> bind(List<String> names, BeanLookup beans, PropertyResolver environment, CopilotMcpProperties properties) {
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        for (String reference : names) {
            String name = environment.resolveRequiredPlaceholders(reference);
            Assert.hasText(name, "MCP server references must not be blank");
            Assert.isTrue(!servers.containsKey(name), "Duplicate MCP server reference: " + name);
            var configured = properties.servers().get(name);
            boolean nativeBean = beans.containsBean(name) && beans.isTypeMatch(name, McpServerConfig.class);
            Assert.isTrue(configured == null || !nativeBean,
                    "Ambiguous MCP server definition in properties and SDK bean: " + name);
            Assert.isTrue(configured != null || nativeBean, () -> "Unknown MCP server '" + name
                    + "': define quarkus.copilot.mcp.servers." + name + " or an SDK McpServerConfig bean @Named '" + name + "'");
            servers.put(name, configured == null ? beans.getBean(name, McpServerConfig.class) : configured.toServerConfig());
        }
        return Map.copyOf(servers);
    }
}
