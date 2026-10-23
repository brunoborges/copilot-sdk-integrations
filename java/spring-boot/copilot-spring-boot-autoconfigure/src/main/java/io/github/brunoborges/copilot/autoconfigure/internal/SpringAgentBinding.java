package io.github.brunoborges.copilot.autoconfigure.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.brunoborges.copilot.agent.internal.BindingContext;
import io.github.brunoborges.copilot.agent.internal.ContextPropagation;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentExecutionLimits;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentSettings;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentExecutionProperties;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsProperties;
import io.github.brunoborges.copilot.autoconfigure.CopilotMcpProperties;
import io.github.brunoborges.copilot.autoconfigure.CopilotProviderProperties;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.ClassUtils;

/** Adapts Spring beans, environment, resources, and configuration properties to the shared agent engine. */
public final class SpringAgentBinding {

    private SpringAgentBinding() {
    }

    /**
     * Creates the binding context. MCP server and provider properties are looked up lazily from the bean factory so
     * that they are optional, as they are for applications that do not enable them.
     */
    public static BindingContext context(BeanFactory beans, Environment environment, ResourceLoader resources,
            CopilotAgentsProperties properties) {
        var loader = resources == null ? new DefaultResourceLoader() : resources;
        return new BindingContext(new SpringBeanLookup(beans), environment::resolveRequiredPlaceholders,
                location -> loader.getResource(location).getInputStream(), settings(properties),
                name -> {
                    var mcp = beans.getBeanProvider(CopilotMcpProperties.class).getIfAvailable();
                    var server = mcp == null ? null : mcp.servers().get(name);
                    return server == null ? null : server.toServerConfig();
                },
                name -> {
                    var providers = beans.getBeanProvider(CopilotProviderProperties.class).getIfAvailable();
                    var provider = providers == null ? null : providers.providers().get(name);
                    return provider == null ? null : provider.toProviderConfig();
                },
                "copilot");
    }

    /** Converts Spring-bound agent properties into the engine's settings. */
    public static CopilotAgentSettings settings(CopilotAgentsProperties properties) {
        if (properties == null) {
            return new CopilotAgentSettings(Map.of());
        }
        var agents = new LinkedHashMap<String, CopilotAgentSettings.Agent>();
        properties.agents().forEach((name, agent) -> {
            var permissions = agent.permissions();
            agents.put(name, new CopilotAgentSettings.Agent(agent.model(), agent.reasoningEffort(),
                    agent.responseTimeout(), agent.mcpServers(), agent.provider(), agent.availableTools(),
                    agent.excludedTools(), agent.excludedBuiltInAgents(), agent.builtInTools(), agent.workingDirectory(),
                    agent.additionalDirectories(), permissions == null ? null
                            : new CopilotAgentSettings.Permissions(permissions.allow(), permissions.allowTools(),
                                    permissions.allowMcpServers(), permissions.allowAgentTools(),
                                    permissions.allowReadOnly()),
                    agent.toolSearch() == null ? null : new CopilotAgentSettings.ToolSearch(
                            agent.toolSearch().enabled(), agent.toolSearch().deferThreshold()),
                    agent.messageSource()));
        });
        return new CopilotAgentSettings(agents);
    }

    /** Converts Spring-bound execution properties into the engine's limits. */
    public static CopilotAgentExecutionLimits limits(CopilotAgentExecutionProperties execution) {
        return execution == null ? CopilotAgentExecutionLimits.DEFAULT
                : new CopilotAgentExecutionLimits(execution.maxConcurrentInvocations(),
                        execution.maxOutstandingInvocations());
    }

    /**
     * Uses Micrometer Context Propagation when it is on the classpath, which restores every registered
     * {@code ThreadLocalAccessor} (such as Spring Security's {@code SecurityContextHolder} and Micrometer
     * observations) plus the SLF4J MDC; otherwise propagation is a no-op.
     */
    public static ContextPropagation contextPropagation(ClassLoader classLoader) {
        if (ClassUtils.isPresent("io.micrometer.context.ContextSnapshotFactory", classLoader)) {
            return new MicrometerContextPropagation(classLoader);
        }
        return ContextPropagation.NONE;
    }
}
