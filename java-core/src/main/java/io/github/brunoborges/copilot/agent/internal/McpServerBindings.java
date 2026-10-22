package io.github.brunoborges.copilot.agent.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.copilot.rpc.McpServerConfig;

final class McpServerBindings {

    private McpServerBindings() {
    }

    static Map<String, McpServerConfig> bind(List<String> names, BindingContext binding) {
        var beans = binding.beans();
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        for (String reference : names) {
            String name = binding.environment().resolveRequiredPlaceholders(reference);
            Assert.hasText(name, "MCP server references must not be blank");
            Assert.isTrue(!servers.containsKey(name), "Duplicate MCP server reference: " + name);
            var configured = binding.mcpServers().apply(name);
            boolean nativeBean = beans.containsBean(name) && beans.isTypeMatch(name, McpServerConfig.class);
            Assert.isTrue(configured == null || !nativeBean,
                    "Ambiguous MCP server definition in properties and SDK bean: " + name);
            Assert.isTrue(configured != null || nativeBean, () -> "Unknown MCP server '" + name + "': define "
                    + binding.configPrefix() + ".mcp.servers." + name + " or an SDK McpServerConfig bean named '" + name + "'");
            servers.put(name, configured == null ? beans.getBean(name, McpServerConfig.class) : configured);
        }
        return Map.copyOf(servers);
    }
}
