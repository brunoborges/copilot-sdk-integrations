package io.github.brunoborges.copilot.micronaut;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Singleton;

import io.github.brunoborges.copilot.agent.internal.BindingContext;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentFactory;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.agent.internal.ResourceLookup;
import io.github.brunoborges.copilot.micronaut.config.CopilotConfigs;
import io.github.brunoborges.copilot.micronaut.config.CopilotRuntimeConfig;
import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;

/**
 * Holds one {@link CopilotAgentFactory} per {@code @CopilotAgent} interface registered at compile time. Every agent
 * is validated during startup so misconfiguration fails fast.
 */
@Singleton
@Requires(property = CopilotRuntimeConfig.PREFIX + ".enabled", notEquals = "false")
public class CopilotAgentRegistry {

    private final CopilotAgentInvoker invoker;
    private final CopilotRuntimeConfig config;
    private final BeanContext beanContext;
    private final Environment environment;
    private final Map<String, Class<?>> agents = new LinkedHashMap<>();
    private final Map<String, CopilotAgentFactory> factories = new ConcurrentHashMap<>();
    private volatile BindingContext binding;

    public CopilotAgentRegistry(CopilotAgentInvoker invoker, CopilotRuntimeConfig config, BeanContext beanContext,
            Environment environment, List<CopilotAgentRegistration> registrations) {
        this.invoker = invoker;
        this.config = config;
        this.beanContext = beanContext;
        this.environment = environment;
        registrations.stream().map(CopilotAgentRegistration::agentType)
                .sorted(java.util.Comparator.comparing(Class::getName))
                .forEach(type -> agents.put(type.getName(), type));
    }

    /** Returns the factory of a registered agent type, or {@code null} when the type was not registered. */
    public CopilotAgentFactory factory(Class<?> type) {
        if (type == null || agents.get(type.getName()) != type) {
            return null;
        }
        return factories.computeIfAbsent(type.getName(), name -> new CopilotAgentFactory(type, invoker, binding()));
    }

    /** Returns the injectable proxy of an explicit-ID agent. */
    public <T> T proxy(Class<T> type) {
        var factory = factory(type);
        if (factory == null) {
            throw new IllegalStateException("Copilot agent " + type.getName() + " is not registered; add "
                    + "copilot-micronaut-processor to the annotation processor path");
        }
        return type.cast(factory.getObject());
    }

    /** Every registered agent's factory, initializing any not yet created. */
    public List<CopilotAgentFactory> all() {
        var result = new ArrayList<CopilotAgentFactory>();
        for (Class<?> type : agents.values()) {
            result.add(factory(type));
        }
        return result;
    }

    private BindingContext binding() {
        var current = binding;
        if (current == null) {
            var mcp = CopilotConfigs.mcp(config);
            var providers = CopilotConfigs.providers(config);
            current = new BindingContext(new MicronautBeanLookup(beanContext),
                    new EnvironmentPropertyResolver(environment),
                    ResourceLookup.classpath(environment.getClassLoader()),
                    CopilotConfigs.agents(config),
                    name -> mcp.servers().containsKey(name) ? mcp.servers().get(name).toServerConfig() : null,
                    name -> providers.providers().containsKey(name)
                            ? providers.providers().get(name).toProviderConfig() : null,
                    CopilotRuntimeConfig.PREFIX);
            binding = current;
        }
        return current;
    }
}
