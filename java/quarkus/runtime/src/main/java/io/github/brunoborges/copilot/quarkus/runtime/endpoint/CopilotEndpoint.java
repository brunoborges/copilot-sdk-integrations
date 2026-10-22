package io.github.brunoborges.copilot.quarkus.runtime.endpoint;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.ConfigProvider;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.SessionMetadata;
import com.github.copilot.rpc.ToolDefinition;

import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentDefinition;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentFactory;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.quarkus.runtime.ConfigPropertyResolver;
import io.github.brunoborges.copilot.quarkus.runtime.CopilotAgentRegistry;
import io.github.brunoborges.copilot.quarkus.runtime.CopilotRuntimeAccess;
import io.github.brunoborges.copilot.quarkus.runtime.config.CopilotRuntimeConfig;

/**
 * Read-only diagnostics served at {@code /q/copilot}: registered agents, execution capacity, and, when the client
 * is connected, the runtime's models and sessions.
 * <p>
 * The endpoint never starts a lazily configured client unless {@code quarkus.copilot.endpoint.start-client} is
 * enabled. Credentials, tool arguments, and provider secrets are never reported; agent instructions are reported
 * only when {@code quarkus.copilot.endpoint.show-instructions} is enabled.
 */
@Singleton
public class CopilotEndpoint {

    static final String MANAGED_SESSION_PREFIX = "agent-";

    private final CopilotClient client;
    private final CopilotRuntimeConfig.Endpoint properties;
    private final CopilotRuntimeConfig config;
    private final CopilotAgentRegistry registry;
    private final CopilotAgentInvoker invoker;

    @Inject
    public CopilotEndpoint(CopilotClient client, CopilotRuntimeConfig config, CopilotAgentRegistry registry,
            CopilotAgentInvoker invoker) {
        this.client = client;
        this.config = config;
        this.properties = config.endpoint();
        this.registry = registry;
        this.invoker = invoker;
    }

    /** Summarizes local state only; it never contacts the Copilot runtime. */
    public CopilotDescriptor copilot() {
        return new CopilotDescriptor(CopilotRuntimeAccess.state(client), agents(), execution());
    }

    /** Returns one of {@code agents}, {@code execution}, {@code models}, or {@code sessions}, else {@code null}. */
    public Object section(String section) {
        return switch (section) {
            case "agents" -> agents();
            case "execution" -> execution();
            case "models" -> models();
            case "sessions" -> sessions();
            default -> null;
        };
    }

    public List<AgentDescriptor> agents() {
        return registry.all().stream().map(this::describe).sorted(Comparator.comparing(AgentDescriptor::name)).toList();
    }

    public ExecutionDescriptor execution() {
        var stats = invoker.executionStats();
        return new ExecutionDescriptor(stats.running(), stats.queued(), stats.maxConcurrent(), stats.maxOutstanding());
    }

    public ModelsDescriptor models() {
        var result = query(client::listModels, models -> models.stream().filter(Objects::nonNull)
                .map(model -> new ModelDescriptor(model.getId(), model.getName(),
                        model.getPolicy() == null ? null : model.getPolicy().getState(),
                        model.getSupportedReasoningEfforts(), model.getDefaultReasoningEffort()))
                .toList());
        return new ModelsDescriptor(result.state(), result.reason(), result.value());
    }

    public SessionsDescriptor sessions() {
        var result = query(client::listSessions, sessions -> sessions.stream().filter(Objects::nonNull)
                .filter(session -> properties.includeAllSessions() || managed(session))
                .map(session -> new SessionDescriptor(session.getSessionId(),
                        properties.showSessionSummaries() ? session.getSummary() : null,
                        session.getStartTime(), session.getModifiedTime(), session.isRemote(), managed(session)))
                .toList());
        return new SessionsDescriptor(result.state(), result.reason(), result.value());
    }

    private static boolean managed(SessionMetadata session) {
        return session.getSessionId() != null && session.getSessionId().startsWith(MANAGED_SESSION_PREFIX);
    }

    private <T, R> RuntimeResult<R> query(Supplier<CompletableFuture<List<T>>> call, Function<List<T>, R> mapper) {
        long deadline = System.nanoTime() + properties.timeout().toNanos();
        try {
            String unavailable = CopilotRuntimeAccess.connect(client, properties.startClient(), deadline);
            if (unavailable != null) {
                return new RuntimeResult<>(CopilotRuntimeAccess.state(client), unavailable, null);
            }
            List<T> items = CopilotRuntimeAccess.await(call.get(), deadline);
            return new RuntimeResult<>(CopilotRuntimeAccess.state(client), null,
                    mapper.apply(items == null ? List.of() : items));
        } catch (TimeoutException ex) {
            return failure("Copilot runtime did not respond within " + properties.timeout());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failure("Interrupted while querying the Copilot runtime");
        } catch (ExecutionException | RuntimeException ex) {
            return failure("Copilot runtime query failed: " + CopilotRuntimeAccess.error(ex));
        }
    }

    private <R> RuntimeResult<R> failure(String reason) {
        return new RuntimeResult<>(CopilotRuntimeAccess.state(client), reason, null);
    }

    private AgentDescriptor describe(CopilotAgentFactory factory) {
        CopilotAgentDefinition definition = factory.definition();
        var system = definition.systemMessage();
        return new AgentDescriptor(definition.name(), definition.typeName(), factory.boundDefinition(),
                emptyToNull(definition.model()), emptyToNull(definition.reasoningEffort()),
                definition.responseTimeout() == null ? null : definition.responseTimeout().toString(),
                definition.provider() == null ? null
                        : new ProviderDescriptor(providerName(factory.agentType(), definition.name()),
                                definition.provider().getType()),
                definition.tools().stream().map(ToolDefinition::name).sorted().toList(),
                definition.mcpServers().keySet().stream().sorted().toList(),
                system == null || system.getMode() == null ? null : system.getMode().name(),
                properties.showInstructions() && system != null ? system.getContent() : null);
    }

    private String providerName(Class<?> type, String name) {
        var agent = config.agents().get(name);
        if (agent != null && agent.provider().isPresent()) {
            return agent.provider().get();
        }
        var annotation = type.getAnnotation(CopilotAgent.class);
        if (annotation == null) {
            return null;
        }
        try {
            return emptyToNull(new ConfigPropertyResolver(ConfigProvider.getConfig())
                    .resolveRequiredPlaceholders(annotation.provider()));
        } catch (IllegalArgumentException ex) {
            return annotation.provider();
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private record RuntimeResult<R>(String state, String reason, R value) {
    }

    public record CopilotDescriptor(String state, List<AgentDescriptor> agents, ExecutionDescriptor execution) {
    }

    /**
     * @param instructions system message content; {@code null} unless
     *        {@code quarkus.copilot.endpoint.show-instructions=true}
     */
    public record AgentDescriptor(String name, String type, boolean bound, String model, String reasoningEffort,
            String responseTimeout, ProviderDescriptor provider, List<String> tools, List<String> mcpServers,
            String instructionsMode, String instructions) {
    }

    public record ProviderDescriptor(String name, String type) {
    }

    public record ExecutionDescriptor(int running, int queued, int maxConcurrentInvocations,
            int maxOutstandingInvocations) {
    }

    public record ModelsDescriptor(String state, String reason, List<ModelDescriptor> models) {
    }

    public record ModelDescriptor(String id, String name, String policyState, List<String> supportedReasoningEfforts,
            String defaultReasoningEffort) {
    }

    public record SessionsDescriptor(String state, String reason, List<SessionDescriptor> sessions) {
    }

    /** @param managed whether the session was created by this extension's agents */
    public record SessionDescriptor(String sessionId, String summary, String startTime, String modifiedTime,
            boolean remote, boolean managed) {
    }
}
