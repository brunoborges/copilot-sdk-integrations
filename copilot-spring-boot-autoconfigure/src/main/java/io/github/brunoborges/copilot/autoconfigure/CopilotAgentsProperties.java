package io.github.brunoborges.copilot.autoconfigure;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

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
     * @param mcpServers server names overriding the annotation; null retains annotation selection
     * @param provider BYOK provider name overriding the annotation; null retains annotation selection,
     *                 empty selects none
     */
    public record Agent(
            String model,
            String reasoningEffort,
            @DefaultValue("60s") Duration responseTimeout,
            List<String> mcpServers,
            String provider) {

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
        }

        public Agent(String model, String reasoningEffort, Duration responseTimeout) {
            this(model, reasoningEffort, responseTimeout, null, null);
        }

        public Agent(String model, String reasoningEffort, Duration responseTimeout, List<String> mcpServers) {
            this(model, reasoningEffort, responseTimeout, mcpServers, null);
        }
    }
}
