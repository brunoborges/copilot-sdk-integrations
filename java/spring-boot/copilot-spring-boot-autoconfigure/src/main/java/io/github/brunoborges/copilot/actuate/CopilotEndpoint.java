package io.github.brunoborges.copilot.actuate;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.ModelInfo;
import com.github.copilot.rpc.SessionMetadata;
import com.github.copilot.rpc.ToolDefinition;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentDefinition;
import io.github.brunoborges.copilot.autoconfigure.internal.CopilotAgentFactoryBean;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsProperties;
import io.github.brunoborges.copilot.autoconfigure.CopilotEndpointProperties;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.actuate.endpoint.Access;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;

/**
 * Read-only {@code copilot} actuator endpoint exposing registered agents, execution capacity, and,
 * when the client is connected, the runtime's models and sessions.
 * <p>
 * The endpoint never starts a lazily configured client unless {@code copilot.endpoint.start-client} is
 * enabled. Credentials, tool arguments, and provider secrets are never reported; agent instructions are
 * reported only when {@code copilot.endpoint.show-instructions} is enabled.
 */
@Endpoint(id = "copilot", defaultAccess = Access.READ_ONLY)
public class CopilotEndpoint {

    static final String MANAGED_SESSION_PREFIX = "agent-";

    private final CopilotClient client;
    private final CopilotEndpointProperties properties;
    private final ListableBeanFactory beans;
    private final Environment environment;

    public CopilotEndpoint(CopilotClient client, CopilotEndpointProperties properties, ListableBeanFactory beans,
            Environment environment) {
        Assert.notNull(client, "CopilotClient is required");
        Assert.notNull(properties, "CopilotEndpointProperties is required");
        Assert.notNull(beans, "ListableBeanFactory is required");
        Assert.notNull(environment, "Environment is required");
        this.client = client;
        this.properties = properties;
        this.beans = beans;
        this.environment = environment;
    }

    /** Summarizes local state only; it never contacts the Copilot runtime. */
    @ReadOperation
    public CopilotDescriptor copilot() {
        return new CopilotDescriptor(CopilotRuntimeAccess.state(client), agents(), execution());
    }

    /** Returns one of {@code agents}, {@code execution}, {@code models}, or {@code sessions}. */
    @ReadOperation
    public Object section(@Selector String section) {
        return switch (section) {
            case "agents" -> agents();
            case "execution" -> execution();
            case "models" -> models();
            case "sessions" -> sessions();
            default -> null;
        };
    }

    public List<AgentDescriptor> agents() {
        var settings = beans.getBeanProvider(CopilotAgentsProperties.class).getIfAvailable();
        return beans.getBeansOfType(CopilotAgentFactoryBean.class).values().stream()
                .filter(factory -> factory.definition() != null)
                .map(factory -> describe(factory, settings))
                .sorted(Comparator.comparing(AgentDescriptor::name))
                .toList();
    }

    public ExecutionDescriptor execution() {
        var invoker = beans.getBeanProvider(CopilotAgentInvoker.class).getIfAvailable();
        if (invoker == null) {
            return null;
        }
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
        String state;
        try {
            state = CopilotRuntimeAccess.state(client);
        } catch (RuntimeException ex) {
            state = "UNKNOWN";
        }
        return new RuntimeResult<>(state, reason, null);
    }

    private AgentDescriptor describe(CopilotAgentFactoryBean factory, CopilotAgentsProperties settings) {
        CopilotAgentDefinition definition = factory.definition();
        var system = definition.systemMessage();
        return new AgentDescriptor(definition.name(), definition.typeName(), factory.boundDefinition(),
                emptyToNull(definition.model()), emptyToNull(definition.reasoningEffort()),
                definition.responseTimeout() == null ? null : definition.responseTimeout().toString(),
                definition.provider() == null ? null
                        : new ProviderDescriptor(providerName(factory.agentType(), definition.name(), settings),
                                definition.provider().getType()),
                definition.tools().stream().map(ToolDefinition::name).sorted().toList(),
                definition.mcpServers().keySet().stream().sorted().toList(),
                system == null || system.getMode() == null ? null : system.getMode().name(),
                properties.showInstructions() && system != null ? system.getContent() : null);
    }

    private String providerName(Class<?> type, String name, CopilotAgentsProperties settings) {
        var agent = settings == null ? null : settings.agents().get(name);
        if (agent != null && agent.provider() != null) {
            return agent.provider();
        }
        var annotation = type.getAnnotation(CopilotAgent.class);
        return annotation == null ? null : emptyToNull(environment.resolvePlaceholders(annotation.provider()));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private record RuntimeResult<R>(String state, String reason, R value) {
    }

    public record CopilotDescriptor(String state, List<AgentDescriptor> agents, ExecutionDescriptor execution) {
    }

    /**
     * @param instructions system message content; {@code null} unless {@code copilot.endpoint.show-instructions=true}
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

    /** @param managed whether the session was created by this starter's agents */
    public record SessionDescriptor(String sessionId, String summary, String startTime, String modifiedTime,
            boolean remote, boolean managed) {
    }
}
