package com.microsoft.copilot.integrations.springboot.autoconfigure;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

import com.microsoft.copilot.integrations.core.CopilotMessageSources;

/** @param agents execution settings keyed by Copilot agent name */
@ConfigurationProperties("copilot")
public record CopilotAgentsProperties(Map<String, Agent> agents) {

    /** Response timeout applied to agents without explicit execution settings. */
    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    public CopilotAgentsProperties {
        agents = agents == null ? Map.of() : Map.copyOf(agents);
    }

    /**
     * @param model model override; omitted values retain the annotation/runtime choice
     * @param reasoningEffort model-specific reasoning effort override
     * @param responseTimeout maximum response wait, excluding session lookup and cleanup; synchronous agent
     *                        methods wait at most this long in total
     * @param mcpServers MCP server names, each a {@code copilot.mcp.servers} entry or an SDK bean of that name,
     *                   overriding the annotation; null retains annotation selection
     * @param provider BYOK provider name, a {@code copilot.providers} entry or an SDK bean of that name,
     *                 overriding the annotation; null retains annotation selection, empty selects none
     * @param availableTools runtime tool allow-list overriding the annotation; null retains the annotation,
     *                       empty removes the allow-list
     * @param excludedTools runtime tool deny-list overriding the annotation; null retains the annotation
     * @param excludedBuiltInAgents built-in subagent names hidden from the session; null retains the annotation
     * @param builtInTools {@code false} excludes every runtime built-in tool; null retains the annotation
     * @param workingDirectory session working directory; null retains the annotation, empty selects the runtime default
     * @param additionalDirectories directories accessible beyond the working directory; null retains the annotation
     * @param permissions declarative permission policy used when the agent has no permission handler bean
     * @param toolSearch runtime tool-search overrides; each unset value retains the annotation
     * @param messageSource default prompt provenance ({@code user}, {@code system}, or {@code agent-<id>}); null
     *                      retains the annotation, empty omits the source. Not an authenticated identity.
     */
    public record Agent(
            String model,
            String reasoningEffort,
            @DefaultValue("60s") Duration responseTimeout,
            List<String> mcpServers,
            String provider,
            List<String> availableTools,
            List<String> excludedTools,
            List<String> excludedBuiltInAgents,
            Boolean builtInTools,
            String workingDirectory,
            List<String> additionalDirectories,
            Permissions permissions,
            ToolSearch toolSearch,
            String messageSource) {

        @ConstructorBinding
        public Agent {
            Assert.isTrue(model == null || !model.isBlank(), "Agent model must not be blank");
            Assert.isTrue(reasoningEffort == null || !reasoningEffort.isBlank(), "Agent reasoning-effort must not be blank");
            Assert.notNull(responseTimeout, "Agent response-timeout is required");
            Assert.isTrue(responseTimeout.compareTo(Duration.ofMillis(1)) >= 0
                            && responseTimeout.compareTo(Duration.ofNanos(Long.MAX_VALUE)) <= 0,
                    "Agent response-timeout must be at least 1ms and representable in nanoseconds");
            if (mcpServers != null) {
                mcpServers = List.copyOf(mcpServers);
                mcpServers.forEach(name -> Assert.hasText(name, "Agent MCP server references must not be blank"));
            }
            Assert.isTrue(provider == null || provider.isEmpty() || !provider.isBlank(),
                    "Agent provider must not be blank");
            availableTools = names(availableTools, "available-tools");
            excludedTools = names(excludedTools, "excluded-tools");
            excludedBuiltInAgents = names(excludedBuiltInAgents, "excluded-built-in-agents");
            Assert.isTrue(workingDirectory == null || workingDirectory.isEmpty() || !workingDirectory.isBlank(),
                    "Agent working-directory must not be blank");
            additionalDirectories = names(additionalDirectories, "additional-directories");
            if (messageSource != null) {
                CopilotMessageSources.parse(messageSource);
            }
        }

        public Agent(String model, String reasoningEffort, Duration responseTimeout, List<String> mcpServers,
                String provider, List<String> availableTools, List<String> excludedTools,
                List<String> excludedBuiltInAgents, Boolean builtInTools, String workingDirectory,
                List<String> additionalDirectories, Permissions permissions) {
            this(model, reasoningEffort, responseTimeout, mcpServers, provider, availableTools, excludedTools,
                    excludedBuiltInAgents, builtInTools, workingDirectory, additionalDirectories, permissions, null, null);
        }

        public Agent(String model, String reasoningEffort, Duration responseTimeout, List<String> mcpServers,
                String provider) {
            this(model, reasoningEffort, responseTimeout, mcpServers, provider, null, null, null, null, null, null, null, null, null);
        }

        public Agent(String model, String reasoningEffort, Duration responseTimeout) {
            this(model, reasoningEffort, responseTimeout, null, null);
        }

        public Agent(String model, String reasoningEffort, Duration responseTimeout, List<String> mcpServers) {
            this(model, reasoningEffort, responseTimeout, mcpServers, null);
        }

        private static List<String> names(List<String> values, String property) {
            if (values == null) {
                return null;
            }
            values = List.copyOf(values);
            values.forEach(value -> Assert.hasText(value, "Agent " + property + " entries must not be blank"));
            return values;
        }
    }

    /**
     * Runtime tool search, which defers MCP and external tool definitions behind a tool-search tool once the agent
     * has many tools.
     *
     * @param enabled whether tool search is enabled; null retains the annotation or runtime default
     * @param deferThreshold total tool count at which tools are deferred, at least 1; null retains the annotation
     *                       or runtime default
     */
    public record ToolSearch(Boolean enabled, Integer deferThreshold) {

        public ToolSearch {
            Assert.isTrue(deferThreshold == null || deferThreshold >= 1,
                    "Agent tool-search.defer-threshold must be at least 1");
        }
    }

    /**
     * Declarative permission policy for an agent without a {@code permissionHandler} bean. A request is approved
     * when any configured rule matches; everything else is rejected and logged.
     *
     * @param allow permission request kinds approved unconditionally, such as {@code read} or {@code url}
     * @param allowTools custom-tool or MCP tool names approved for {@code custom-tool} and {@code mcp} requests
     * @param allowMcpServers MCP server names whose tool calls are approved
     * @param allowAgentTools whether calls to the agent's own registered tools are approved
     * @param allowReadOnly whether {@code read} requests and MCP tools declared read-only are approved
     */
    public record Permissions(
            List<String> allow,
            List<String> allowTools,
            List<String> allowMcpServers,
            @DefaultValue("false") boolean allowAgentTools,
            @DefaultValue("false") boolean allowReadOnly) {

        public Permissions {
            allow = Agent.names(allow == null ? List.of() : allow, "permissions.allow");
            allowTools = Agent.names(allowTools == null ? List.of() : allowTools, "permissions.allow-tools");
            allowMcpServers = Agent.names(allowMcpServers == null ? List.of() : allowMcpServers,
                    "permissions.allow-mcp-servers");
        }

        /** @return whether no rule is configured, leaving the deny-by-default behavior in place */
        public boolean isEmpty() {
            return allow.isEmpty() && allowTools.isEmpty() && allowMcpServers.isEmpty() && !allowAgentTools && !allowReadOnly;
        }
    }
}
