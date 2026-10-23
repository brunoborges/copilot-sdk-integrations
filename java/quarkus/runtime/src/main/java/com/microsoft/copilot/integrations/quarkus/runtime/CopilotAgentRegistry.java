package com.microsoft.copilot.integrations.quarkus.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.ConfigProvider;

import com.github.copilot.CopilotClient;

import com.microsoft.copilot.integrations.core.internal.BindingContext;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentFactory;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentInvoker;
import com.microsoft.copilot.integrations.core.internal.ResourceLookup;
import com.microsoft.copilot.integrations.quarkus.runtime.config.CopilotConfigs;
import com.microsoft.copilot.integrations.quarkus.runtime.config.CopilotRuntimeConfig;
import io.quarkus.runtime.StartupEvent;

/**
 * Holds one {@link CopilotAgentFactory} per {@code @CopilotAgent} interface discovered at build time. Every agent
 * is validated during startup so misconfiguration fails fast, as with eagerly created singleton beans.
 */
@Singleton
public class CopilotAgentRegistry {

    private final CopilotAgentInvoker invoker;
    private final CopilotRuntimeConfig config;
    private final Map<String, CopilotAgentFactory> factories = new ConcurrentHashMap<>();
    private volatile BindingContext binding;

    @Inject
    public CopilotAgentRegistry(CopilotAgentInvoker invoker, CopilotRuntimeConfig config) {
        this.invoker = invoker;
        this.config = config;
    }

    void onStart(@Observes @Priority(jakarta.interceptor.Interceptor.Priority.LIBRARY_BEFORE) StartupEvent event,
            CopilotClient client) {
        all();
        if (config.eagerStart()) {
            CopilotClientLifecycle.start(client, config.startupTimeout());
        }
    }

    /** Returns the factory of a discovered agent type, or {@code null} when the type was not discovered. */
    public CopilotAgentFactory factory(Class<?> type) {
        if (type == null || !CopilotAgentCatalog.agents().contains(type.getName())) {
            return null;
        }
        return factories.computeIfAbsent(type.getName(), name -> new CopilotAgentFactory(type, invoker, binding()));
    }

    /** Returns the injectable proxy of an explicit-ID agent. */
    public Object proxy(String type) {
        return factory(load(type)).getObject();
    }

    /** Every discovered agent's factory, initializing any not yet created. */
    public List<CopilotAgentFactory> all() {
        var result = new ArrayList<CopilotAgentFactory>();
        for (String type : CopilotAgentCatalog.agents()) {
            result.add(factory(load(type)));
        }
        return result;
    }

    private BindingContext binding() {
        var current = binding;
        if (current == null) {
            var mpConfig = ConfigProvider.getConfig();
            var mcp = CopilotConfigs.mcp(config);
            var providers = CopilotConfigs.providers(config);
            current = new BindingContext(ArcBeanLookup.current(), new ConfigPropertyResolver(mpConfig),
                    ResourceLookup.classpath(Thread.currentThread().getContextClassLoader()),
                    CopilotConfigs.agents(config),
                    name -> mcp.servers().containsKey(name) ? mcp.servers().get(name).toServerConfig() : null,
                    name -> providers.providers().containsKey(name)
                            ? providers.providers().get(name).toProviderConfig() : null,
                    "quarkus.copilot");
            binding = current;
        }
        return current;
    }

    private static Class<?> load(String type) {
        try {
            return Class.forName(type, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Cannot load Copilot agent " + type, ex);
        }
    }

}
