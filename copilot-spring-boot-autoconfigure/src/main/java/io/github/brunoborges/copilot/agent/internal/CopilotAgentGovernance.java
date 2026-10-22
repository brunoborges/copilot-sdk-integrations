package io.github.brunoborges.copilot.agent.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import com.github.copilot.rpc.McpServerConfig;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.ToolDefinition;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotAgentContext;
import io.github.brunoborges.copilot.agent.CopilotAgentHooks;
import io.github.brunoborges.copilot.agent.CopilotPermissionPolicies;
import io.github.brunoborges.copilot.agent.GlobalCopilotAgentHooks;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsProperties;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;

/**
 * Tool, workspace, and hook governance applied identically to created and resumed agent sessions.
 *
 * @param availableTools effective runtime allow-list, or null for none
 * @param excludedTools effective runtime deny-list, or null for none
 * @param excludedBuiltInAgents built-in subagents to hide, or null for none
 * @param workingDirectory session working directory, or null for the runtime default
 * @param additionalDirectories extra accessible directories, or null for none
 * @param hooks ordered hook beans, global ones first
 */
public record CopilotAgentGovernance(List<String> availableTools, List<String> excludedTools,
        List<String> excludedBuiltInAgents, String workingDirectory, List<String> additionalDirectories,
        List<CopilotAgentHooks> hooks) {

    static final String ALL_BUILT_IN_TOOLS = "builtin:*";

    public CopilotAgentGovernance {
        hooks = List.copyOf(hooks);
    }

    static CopilotAgentGovernance create(String agent, CopilotAgent annotation, CopilotAgentsProperties.Agent settings,
            List<ToolDefinition> tools, Map<String, McpServerConfig> mcpServers, BeanFactory beans,
            Environment environment) {
        List<String> available = select(settings, CopilotAgentsProperties.Agent::availableTools,
                annotation.availableTools(), environment);
        List<String> excluded = select(settings, CopilotAgentsProperties.Agent::excludedTools,
                annotation.excludedTools(), environment);
        boolean builtIns = settings != null && settings.builtInTools() != null
                ? settings.builtInTools() : annotation.builtInTools();
        if (!builtIns) {
            Assert.isTrue(available.isEmpty(), "Copilot agent " + agent
                    + ": built-in-tools=false cannot be combined with available-tools; list the wanted built-in tools instead");
            if (!excluded.contains(ALL_BUILT_IN_TOOLS)) {
                excluded = new ArrayList<>(excluded);
                excluded.add(ALL_BUILT_IN_TOOLS);
            }
        }
        Assert.isTrue(available.isEmpty() || excluded.isEmpty(), "Copilot agent " + agent
                + ": available-tools and excluded-tools are mutually exclusive because the runtime ignores the "
                + "deny-list when an allow-list is set");
        if (!available.isEmpty()) {
            available = new ArrayList<>(available);
            // The runtime filters every tool source, so keep the agent's own tools usable unless explicitly scoped.
            if (!tools.isEmpty() && available.stream().noneMatch(name -> name.startsWith("custom:"))) {
                available.add("custom:*");
            }
            if (!mcpServers.isEmpty() && available.stream().noneMatch(name -> name.startsWith("mcp:"))) {
                available.add("mcp:*");
            }
        }
        List<String> agents = select(settings, CopilotAgentsProperties.Agent::excludedBuiltInAgents,
                annotation.excludedBuiltInAgents(), environment);
        String directory = settings != null && settings.workingDirectory() != null
                ? settings.workingDirectory() : resolve(annotation.workingDirectory(), environment);
        List<String> directories = select(settings, CopilotAgentsProperties.Agent::additionalDirectories,
                annotation.additionalDirectories(), environment);
        List<String> hookNames = select(settings, CopilotAgentsProperties.Agent::hooks, annotation.hooks(), environment);
        return new CopilotAgentGovernance(nullIfEmpty(available), nullIfEmpty(excluded), nullIfEmpty(agents),
                directory.isEmpty() ? null : directory, nullIfEmpty(directories), hooks(hookNames, beans));
    }

    /** Builds the property-driven permission policy, or returns null when none is configured. */
    static PermissionHandler permissions(String agent, CopilotAgentsProperties.Agent settings, boolean handlerBean,
            List<ToolDefinition> tools) {
        var policy = settings == null ? null : settings.permissions();
        if (policy == null || policy.isEmpty()) {
            return null;
        }
        Assert.isTrue(!handlerBean, "Copilot agent " + agent
                + ": permissions properties cannot be combined with a permissionHandler bean");
        var handlers = new ArrayList<PermissionHandler>();
        if (!policy.allow().isEmpty()) {
            handlers.add(CopilotPermissionPolicies.allowKinds(policy.allow()));
        }
        if (!policy.allowTools().isEmpty()) {
            handlers.add(CopilotPermissionPolicies.allowTools(policy.allowTools()));
        }
        if (!policy.allowMcpServers().isEmpty()) {
            handlers.add(CopilotPermissionPolicies.allowMcpServers(policy.allowMcpServers()));
        }
        if (policy.allowAgentTools()) {
            handlers.add(CopilotPermissionPolicies.allowCustomTools(tools));
        }
        if (policy.allowReadOnly()) {
            handlers.add(CopilotPermissionPolicies.allowReadOnly());
        }
        return CopilotPermissionPolicies.anyOf(handlers);
    }

    void apply(CopilotAgentContext context, SessionConfig config, Supplier<CopilotAgentLifetime.Scope> callbackScope) {
        if (availableTools != null) {
            config.setAvailableTools(new ArrayList<>(availableTools));
        }
        if (excludedTools != null) {
            config.setExcludedTools(new ArrayList<>(excludedTools));
        }
        if (excludedBuiltInAgents != null) {
            config.setExcludedBuiltInAgents(excludedBuiltInAgents);
        }
        if (workingDirectory != null) {
            config.setWorkingDirectory(workingDirectory);
        }
        if (additionalDirectories != null) {
            config.setAdditionalDirectories(new ArrayList<>(additionalDirectories));
        }
        if (!hooks.isEmpty()) {
            config.setHooks(CopilotAgentHookChain.sessionHooks(hooks, context, callbackScope));
        }
    }

    void apply(CopilotAgentContext context, ResumeSessionConfig config, Supplier<CopilotAgentLifetime.Scope> callbackScope) {
        if (availableTools != null) {
            config.setAvailableTools(new ArrayList<>(availableTools));
        }
        if (excludedTools != null) {
            config.setExcludedTools(new ArrayList<>(excludedTools));
        }
        if (excludedBuiltInAgents != null) {
            config.setExcludedBuiltInAgents(excludedBuiltInAgents);
        }
        if (workingDirectory != null) {
            config.setWorkingDirectory(workingDirectory);
        }
        if (additionalDirectories != null) {
            config.setAdditionalDirectories(new ArrayList<>(additionalDirectories));
        }
        if (!hooks.isEmpty()) {
            config.setHooks(CopilotAgentHookChain.sessionHooks(hooks, context, callbackScope));
        }
    }

    private static List<String> select(CopilotAgentsProperties.Agent settings,
            Function<CopilotAgentsProperties.Agent, List<String>> property, String[] annotation, Environment environment) {
        if (settings != null && property.apply(settings) != null) {
            return property.apply(settings);
        }
        return Arrays.stream(annotation).map(value -> {
            String resolved = resolve(value, environment);
            Assert.hasText(resolved, "Copilot agent annotation list entries must not be blank");
            return resolved;
        }).toList();
    }

    private static List<CopilotAgentHooks> hooks(List<String> names, BeanFactory beans) {
        var selected = new IdentityHashMap<CopilotAgentHooks, Boolean>();
        var ordered = new ArrayList<CopilotAgentHooks>();
        if (beans instanceof ListableBeanFactory listable) {
            var global = new IdentityHashMap<CopilotAgentHooks, Boolean>();
            for (String name : listable.getBeanNamesForType(CopilotAgentHooks.class)) {
                if (listable.findAnnotationOnBean(name, GlobalCopilotAgentHooks.class) != null) {
                    global.put(listable.getBean(name, CopilotAgentHooks.class), true);
                }
            }
            // orderedStream honours @Order on @Bean methods in an application context; fall back to the
            // annotation-aware comparator for factories without a dependency comparator.
            var sorted = new ArrayList<>(listable.getBeanProvider(CopilotAgentHooks.class).orderedStream()
                    .filter(global::containsKey).toList());
            if (!(listable instanceof DefaultListableBeanFactory factory) || factory.getDependencyComparator() == null) {
                AnnotationAwareOrderComparator.sort(sorted);
            }
            sorted.forEach(hook -> {
                if (selected.put(hook, true) == null) {
                    ordered.add(hook);
                }
            });
        }
        for (String name : names) {
            var hook = beans.getBean(name, CopilotAgentHooks.class);
            if (selected.put(hook, true) == null) {
                ordered.add(hook);
            }
        }
        return ordered;
    }

    private static List<String> nullIfEmpty(List<String> values) {
        return values.isEmpty() ? null : List.copyOf(values);
    }

    private static String resolve(String value, Environment environment) {
        String resolved = environment.resolveRequiredPlaceholders(value);
        Assert.isTrue(resolved.isEmpty() || !resolved.isBlank(), "Copilot agent annotation values must not be blank");
        return resolved;
    }
}
