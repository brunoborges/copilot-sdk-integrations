package io.github.brunoborges.copilot.agent.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.MessageSource;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.ToolDefinition;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.AssistantUsageEvent;
import io.github.brunoborges.copilot.agent.CopilotAgentContext;
import io.github.brunoborges.copilot.agent.CopilotTokenResolver;
import io.github.brunoborges.copilot.agent.CopilotAgentTelemetry;
import io.github.brunoborges.copilot.agent.CopilotAgentEvent;
import io.github.brunoborges.copilot.agent.CopilotConversationNotFoundException;
import io.github.brunoborges.copilot.agent.CopilotMessageSources;


public final class CopilotAgentInvoker implements AutoCloseable {

    private static final Logger log = Logger.getLogger(CopilotAgentInvoker.class.getName());
    /** Name of the agent whose tool handler is running on this thread, for agent-to-agent provenance. */
    private static final ThreadLocal<String> DELEGATING_AGENT = new ThreadLocal<>();
    private final CopilotClient client;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CompletableFuture<Void>> pending = new HashMap<>();
    private final CopilotAgentTelemetry telemetry;
    private final Semaphore concurrent;
    private final Semaphore outstanding;
    private final int maxConcurrent;
    private final int maxOutstanding;
    private final ContextPropagation propagation;
    private volatile boolean closed;

    public CopilotAgentInvoker(CopilotClient client) {
        this(client, CopilotAgentTelemetry.NOOP, CopilotAgentExecutionLimits.DEFAULT);
    }

    public CopilotAgentInvoker(CopilotClient client, CopilotAgentTelemetry telemetry,
            CopilotAgentExecutionLimits execution) {
        this(client, telemetry, execution, ContextPropagation.NONE);
    }

    public CopilotAgentInvoker(CopilotClient client, CopilotAgentTelemetry telemetry,
            CopilotAgentExecutionLimits execution, ContextPropagation propagation) {
        this.client = client;
        this.telemetry = telemetry;
        this.propagation = propagation;
        maxConcurrent = execution.maxConcurrentInvocations();
        maxOutstanding = execution.maxOutstandingInvocations();
        concurrent = new Semaphore(maxConcurrent, true);
        outstanding = new Semaphore(maxOutstanding);
    }

    public CompletableFuture<String> invoke(CopilotAgentDefinition definition, String conversationId, String message) {
        return invokeInternal(definition, conversationId, message, null);
    }

    public CompletableFuture<String> invoke(CopilotAgentDefinition definition, String conversationId, String message,
            Consumer<CopilotAgentEvent> events) {
        Assert.notNull(events, "Streaming invocation requires an event consumer");
        return invokeInternal(definition, conversationId, message, events);
    }

    private CompletableFuture<String> invokeInternal(CopilotAgentDefinition definition, String conversationId, String message,
            Consumer<CopilotAgentEvent> events) {
        return invokeOwned(definition, conversationId, message, events, CopilotAgentLifetime.Lease.NONE, null);
    }

    /**
     * Invokes with a provider-native response schema and completes with the raw structured (JSON) reply.
     * {@code lifetime} is null for explicit-ID agents.
     */
    public CompletableFuture<String> invokeStructured(CopilotAgentDefinition definition, String conversationId,
            String message, Map<String, Object> responseSchema, CopilotAgentLifetime lifetime) {
        Assert.notNull(responseSchema, "Structured invocation requires a response schema");
        return lifetime == null
                ? invokeOwned(definition, conversationId, message, null, CopilotAgentLifetime.Lease.NONE, responseSchema)
                : invokeBound(definition, conversationId, message, null, lifetime, responseSchema);
    }

    public CompletableFuture<String> invokeBound(CopilotAgentDefinition definition, String conversationId, String message,
            Consumer<CopilotAgentEvent> events, CopilotAgentLifetime lifetime) {
        return invokeBound(definition, conversationId, message, events, lifetime, null);
    }

    private CompletableFuture<String> invokeBound(CopilotAgentDefinition definition, String conversationId, String message,
            Consumer<CopilotAgentEvent> events, CopilotAgentLifetime lifetime, Map<String, Object> responseSchema) {
        CopilotAgentLifetime.Lease owner;
        try {
            owner = lifetime.acquire();
        } catch (IllegalStateException ex) {
            return CompletableFuture.failedFuture(ex);
        }
        try (var scope = owner.enter()) {
            return invokeOwned(definition, conversationId, message, events, owner, responseSchema);
        }
    }

    private CompletableFuture<String> invokeOwned(CopilotAgentDefinition definition, String conversationId, String message,
            Consumer<CopilotAgentEvent> events, CopilotAgentLifetime.Lease owner, Map<String, Object> responseSchema) {
        boolean transferred = false;
        try {
            if (conversationId == null || conversationId.isBlank() || message == null || message.isBlank()) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Conversation identity and @UserMessage must be non-blank strings"));
            }
            CopilotAgentContext context;
            InvocationState state;
            try {
                var resolver = definition.tokenResolver();
                String partition = resolver == null ? null : resolver.partitionKey(definition.name(), conversationId);
                context = new CopilotAgentContext(definition.name(), conversationId,
                        sessionId(definition, conversationId, partition));
                state = capture(definition, context, partition);
            } catch (RuntimeException ex) {
                return CompletableFuture.failedFuture(ex);
            }
            String sessionId = context.sessionId();
            var result = new CompletableFuture<String>();
            result.whenComplete((ignored, error) -> {
                if (result.isCancelled()) {
                    state.cancel();
                }
            });
            Consumer<CopilotAgentEvent> subscriber = events == null ? null : event -> {
                if (!result.isCancelled()) {
                    events.accept(event);
                }
            };
            var finished = new CompletableFuture<Void>();
            synchronized (pending) {
                if (closed) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Copilot agent infrastructure is closed"));
                }
                if (!outstanding.tryAcquire()) {
                    var rejection = new RejectedExecutionException("Copilot agent outstanding invocation limit reached");
                    try {
                        telemetry.rejected(definition.name());
                    } catch (RuntimeException ex) {
                        rejection.addSuppressed(ex);
                    }
                    return CompletableFuture.failedFuture(rejection);
                }
                CopilotAgentTelemetry.Call observation;
                try {
                    observation = telemetry.start(definition.name(), definition.model());
                } catch (RuntimeException | Error ex) {
                    outstanding.release();
                    throw ex;
                }
                var previous = pending.put(sessionId, finished);
                transferred = true;
                (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous)
                        .whenComplete((ignored, error) -> {
                            try {
                                executor.execute(() -> execute(definition, context, message, result, finished,
                                        observation, subscriber, owner, responseSchema, state));
                            } catch (RejectedExecutionException ex) {
                                var failure = finishObservation(observation, ex, result.isCancelled());
                                outstanding.release();
                                finish(sessionId, finished);
                                owner.close();
                                result.completeExceptionally(failure);
                            }
                        });
            }
            return result;
        } finally {
            if (!transferred) {
                owner.close();
            }
        }
    }

    /** Resolves the caller's token and context on the caller thread so request-bound state is available. */
    private InvocationState capture(CopilotAgentDefinition definition, CopilotAgentContext context, String partition) {
        var resolver = definition.tokenResolver();
        String token = resolver == null ? null : resolver.resolveToken(context);
        if (token != null && partition == null) {
            throw new IllegalStateException("Copilot agent '" + definition.name() + "': token resolver "
                    + resolver.getClass().getName() + " returned a per-caller token without a partition key; "
                    + "override CopilotTokenResolver.partitionKey to isolate sessions per caller, or return "
                    + "CopilotTokenResolver.SHARED to share them");
        }
        return new InvocationState(token, propagation.capture(), messageSource(definition));
    }

    /**
     * Resolves provenance on the caller thread: an explicit scope, then the agent whose tool is calling, then the
     * agent's configured default. Inference only ever yields an {@code agent-<id>} source, never {@code user}.
     */
    static MessageSource messageSource(CopilotAgentDefinition definition) {
        var scoped = CopilotMessageSources.current();
        if (scoped != null) {
            return scoped;
        }
        var delegating = DELEGATING_AGENT.get();
        return delegating != null ? MessageSource.agent(delegating) : definition.messageSource();
    }

    /**
     * Marks tool handlers so agent invocations they start on their own thread are labeled {@code agent-<name>}, and
     * suspends any caller scope so it cannot relabel those delegated calls.
     */
    private static List<ToolDefinition> attributedTools(List<ToolDefinition> tools, String agent) {
        if (tools == null) {
            return null;
        }
        return tools.stream().map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.parameters(), invocation -> {
            var previous = DELEGATING_AGENT.get();
            DELEGATING_AGENT.set(agent);
            var scope = MessageSourceScope.swap(null);
            try {
                return tool.handler().invoke(invocation);
            } finally {
                MessageSourceScope.swap(scope);
                if (previous == null) {
                    DELEGATING_AGENT.remove();
                } else {
                    DELEGATING_AGENT.set(previous);
                }
            }
        }, tool.overridesBuiltInTool(), tool.skipPermission(), tool.defer(), tool.metadata(), tool.isTerminal(), tool.namespace())).toList();
    }

    /** Enters the bound instance's lifetime and restores the caller's context for SDK-thread callbacks. */
    private static CopilotAgentLifetime.Scope callbackScope(CopilotAgentLifetime.Lease owner, InvocationState state) {
        var instance = owner.enter();
        ContextPropagation.Scope caller;
        try {
            caller = restore(state);
        } catch (RuntimeException | Error ex) {
            instance.close();
            throw ex;
        }
        return () -> {
            try {
                caller.close();
            } finally {
                instance.close();
            }
        };
    }

    private static ContextPropagation.Scope restore(InvocationState state) {
        try {
            return state.context().restore();
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "Cannot restore caller context for a Copilot agent callback", ex);
            return () -> { };
        }
    }

    private void execute(CopilotAgentDefinition definition, CopilotAgentContext context, String message,
            CompletableFuture<String> result, CompletableFuture<Void> finished, CopilotAgentTelemetry.Call observation,
            Consumer<CopilotAgentEvent> events, CopilotAgentLifetime.Lease owner, Map<String, Object> responseSchema,
            InvocationState state) {
        var callerContext = restore(state);
        var instanceScope = owner.enter();
        String response = null;
        Throwable failure = null;
        boolean acquired = false;
        try {
            if (!result.isCancelled()) {
                if (closed) {
                    throw new RejectedExecutionException("Copilot agent infrastructure is closing");
                }
                concurrent.acquire();
                acquired = true;
                if (!result.isCancelled()) {
                    if (closed) {
                        throw new RejectedExecutionException("Copilot agent infrastructure is closing");
                    }
                    try (var scope = observation.openScope()) {
                        response = exchange(definition, context, message, observation, events, owner, responseSchema, state);
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            failure = ex;
        } catch (RuntimeException | IOException ex) {
            failure = ex;
        } catch (Error ex) {
            failure = ex;
        } finally {
            boolean cancelled = result.isCancelled();
            if (cancelled && failure instanceof CancellationException) {
                failure = null;
            }
            try {
                failure = finishObservation(observation, failure, cancelled);
                if (acquired) {
                    concurrent.release();
                }
                outstanding.release();
                finish(context.sessionId(), finished);
            } finally {
                try {
                    owner.close();
                    instanceScope.close();
                } finally {
                    callerContext.close();
                }
            }
        }
        if (failure == null) {
            result.complete(response);
        } else if (!result.completeExceptionally(failure)) {
            log.log(Level.WARNING, "Copilot agent '" + definition.name()
                    + "' failed after its result was already completed or cancelled", failure);
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static Throwable finishObservation(CopilotAgentTelemetry.Call observation, Throwable failure, boolean cancelled) {
        try {
            if (failure != null) {
                observation.error(failure);
            }
            if (cancelled) {
                observation.cancelled();
            }
        } catch (RuntimeException | Error ex) {
            failure = combine(failure, ex);
        }
        try {
            observation.stop();
        } catch (RuntimeException | Error ex) {
            failure = combine(failure, ex);
        }
        return failure;
    }

    private static Throwable combine(Throwable failure, Throwable additional) {
        if (failure == null) {
            return additional;
        }
        if (failure != additional) {
            failure.addSuppressed(additional);
        }
        return failure;
    }

    private String exchange(CopilotAgentDefinition definition, CopilotAgentContext context, String message,
            CopilotAgentTelemetry.Call observation, Consumer<CopilotAgentEvent> events,
            CopilotAgentLifetime.Lease owner, Map<String, Object> responseSchema, InvocationState state) throws IOException {
        var observerFailure = new AtomicReference<RuntimeException>();
        String content;
        try (var session = open(definition, context, observation, events != null, owner, state);
                var subscription = session.on(event -> {
                    try (var callerContext = restore(state); var callbackScope = owner.enter()) {
                        if (event instanceof AssistantUsageEvent usage) {
                            try {
                                observation.usage(usage);
                            } catch (RuntimeException ex) {
                                log.log(Level.WARNING, "Copilot agent usage instrumentation failed", ex);
                                observerFailure.compareAndSet(null, ex);
                            }
                        }
                        for (var listener : definition.listeners()) {
                            try {
                                listener.onEvent(context, event);
                            } catch (RuntimeException ex) {
                                log.log(Level.SEVERE, "Copilot agent '" + definition.name() + "' listener failed", ex);
                                observerFailure.compareAndSet(null, ex);
                            }
                        }
                        if (events != null) {
                            try {
                                events.accept(new CopilotAgentEvent(context, event));
                            } catch (RuntimeException ex) {
                                log.log(Level.SEVERE, "Copilot agent streaming observer failed", ex);
                                observerFailure.compareAndSet(null, ex);
                            }
                        }
                    }
                })) {
            Assert.isTrue(context.sessionId().equals(session.getSessionId()),
                    "Copilot runtime did not preserve the requested agent session ID");
            var options = new MessageOptions().setPrompt(message);
            if (state.messageSource() != null) {
                options.setSource(state.messageSource());
            }
            if (responseSchema != null) {
                options.setResponseSchema(responseSchema);
            }
            state.checkCancelled();
            var reply = await(session, definition, state, state.awaiting(session.sendAndWait(
                    options, definition.responseTimeout().toMillis())));
            if (events == null) {
                Assert.state(reply != null && reply.getData() != null && reply.getData().content() != null,
                        "Copilot agent completed without an assistant text response");
            }
            content = reply == null || reply.getData() == null ? null : reply.getData().content();
        }
        if (observerFailure.get() != null) {
            throw new IllegalStateException("Copilot agent listener failed", observerFailure.get());
        }
        return content;
    }

    /** Waits for the reply, aborting the runtime turn when the caller cancels or the response times out. */
    private static AssistantMessageEvent await(CopilotSession session, CopilotAgentDefinition definition,
            InvocationState state, CompletableFuture<AssistantMessageEvent> reply) {
        try {
            return reply.join();
        } catch (CancellationException ex) {
            if (state.cancelled()) {
                InvocationState.abort(session, definition.name());
            }
            throw ex;
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof TimeoutException) {
                InvocationState.abort(session, definition.name());
            }
            throw ex;
        }
    }

    private CopilotSession open(CopilotAgentDefinition definition, CopilotAgentContext context,
            CopilotAgentTelemetry.Call observation, boolean streaming, CopilotAgentLifetime.Lease owner,
            InvocationState state) {
        // Null is the SDK's documented not-found result; lookup errors must not create a new conversation.
        if (state.join(client.getSessionMetadata(context.sessionId()), definition.responseTimeout(), null) == null) {
            if (owner.requiresHistory()) {
                throw new CopilotConversationNotFoundException(definition.name());
            }
            var config = new SessionConfig().setSessionId(context.sessionId())
                    .setOnPermissionRequest(definition.permissions());
            if (!definition.model().isEmpty()) {
                config.setModel(definition.model());
            }
            if (!definition.reasoningEffort().isEmpty()) {
                config.setReasoningEffort(definition.reasoningEffort());
            }
            config.setSystemMessage(definition.systemMessage()).setTools(definition.tools());
            if (!definition.mcpServers().isEmpty()) {
                config.setMcpServers(definition.mcpServers());
            }
            if (definition.provider() != null) {
                config.setProvider(definition.provider());
            }
            definition.governance().apply(context, config, () -> callbackScope(owner, state));
            definition.customizers().forEach(customizer -> customizer.customize(context, config));
            if (streaming) {
                config.setStreaming(true);
            }
            if (state.gitHubToken() != null && config.getGitHubToken() == null && config.getGitHubTokenProvider() == null) {
                config.setGitHubToken(state.gitHubToken());
            }
            config.setTools(propagatedTools(observedTools(attributedTools(config.getTools(), definition.name()), observation, owner), state));
            Assert.isTrue(context.sessionId().equals(config.getSessionId()), "Agent customizers must not change the session ID");
            Assert.notNull(config.getOnPermissionRequest(), "Agent customizers must not remove the permission handler");
            config.setOnPermissionRequest(propagatedPermissions(ownedPermissions(config.getOnPermissionRequest(), owner), state));
            var session = state.join(client.createSession(config), definition.responseTimeout(), CopilotAgentInvoker::closeQuietly);
            owner.sessionOpened();
            return session;
        }
        var config = new ResumeSessionConfig().setOnPermissionRequest(definition.permissions());
        if (!definition.model().isEmpty()) {
            config.setModel(definition.model());
        }
        if (!definition.reasoningEffort().isEmpty()) {
            config.setReasoningEffort(definition.reasoningEffort());
        }
        config.setSystemMessage(definition.systemMessage()).setTools(definition.tools());
        if (!definition.mcpServers().isEmpty()) {
            config.setMcpServers(definition.mcpServers());
        }
        // Provider credentials are not persisted with the session, so resumption must resupply them.
        if (definition.provider() != null) {
            config.setProvider(definition.provider());
        }
        definition.governance().apply(context, config, () -> callbackScope(owner, state));
        definition.customizers().forEach(customizer -> customizer.customize(context, config));
        if (streaming) {
            config.setStreaming(true);
        }
        // Session tokens are not persisted with the session, so resumption must resupply them.
        if (state.gitHubToken() != null && config.getGitHubToken() == null && config.getGitHubTokenProvider() == null) {
            config.setGitHubToken(state.gitHubToken());
        }
        config.setTools(propagatedTools(observedTools(attributedTools(config.getTools(), definition.name()), observation, owner), state));
        Assert.notNull(config.getOnPermissionRequest(), "Agent customizers must not remove the permission handler");
        config.setOnPermissionRequest(propagatedPermissions(ownedPermissions(config.getOnPermissionRequest(), owner), state));
        var session = state.join(client.resumeSession(context.sessionId(), config), definition.responseTimeout(), CopilotAgentInvoker::closeQuietly);
        owner.sessionOpened();
        return session;
    }

    private static void closeQuietly(CopilotSession session) {
        try {
            session.close();
        } catch (Exception ex) {
            log.log(Level.WARNING, "Failed to close an abandoned Copilot session", ex);
        }
    }

    private static PermissionHandler ownedPermissions(
            PermissionHandler permissions, CopilotAgentLifetime.Lease owner) {
        if (!owner.bound()) {
            return permissions;
        }
        return (request, invocation) -> {
            try (var scope = owner.enter()) {
                return permissions.handle(request, invocation);
            }
        };
    }

    private static PermissionHandler propagatedPermissions(PermissionHandler permissions, InvocationState state) {
        if (state.context() == ContextPropagation.Snapshot.NONE) {
            return permissions;
        }
        return (request, invocation) -> {
            try (var scope = restore(state)) {
                return permissions.handle(request, invocation);
            }
        };
    }

    private static List<ToolDefinition> propagatedTools(List<ToolDefinition> tools, InvocationState state) {
        if (tools == null || state.context() == ContextPropagation.Snapshot.NONE) {
            return tools;
        }
        return tools.stream().map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.parameters(), invocation -> {
            try (var scope = restore(state)) {
                return tool.handler().invoke(invocation);
            }
        }, tool.overridesBuiltInTool(), tool.skipPermission(), tool.defer(), tool.metadata(), tool.isTerminal(), tool.namespace())).toList();
    }

    private List<ToolDefinition> observedTools(List<ToolDefinition> tools,
            CopilotAgentTelemetry.Call observation, CopilotAgentLifetime.Lease owner) {
        if (tools == null || (telemetry == CopilotAgentTelemetry.NOOP && !owner.bound())) {
            return tools;
        }
        return tools.stream().map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.parameters(), invocation -> {
            var call = observation.tool((tool.namespace() == null ? "" : tool.namespace().name() + ".") + tool.name());
            try (var instanceScope = owner.enter(); var scope = call.openScope()) {
                var response = tool.handler().invoke(invocation);
                Assert.notNull(response, "Copilot tool handler returned a null future");
                return response.whenComplete((value, error) -> {
                    var failure = finishObservation(call, error, false);
                    if (error == null && failure != null) {
                        throw new CompletionException(failure);
                    }
                });
            } catch (RuntimeException | Error ex) {
                finishObservation(call, ex, false);
                throw ex;
            }
        }, tool.overridesBuiltInTool(), tool.skipPermission(), tool.defer(), tool.metadata(), tool.isTerminal(), tool.namespace())).toList();
    }

    private void finish(String sessionId, CompletableFuture<Void> finished) {
        synchronized (pending) {
            pending.remove(sessionId, finished);
        }
        finished.complete(null);
    }

    public static String sessionId(CopilotAgentDefinition definition, String conversationId) {
        return sessionId(definition, conversationId, null);
    }

    /** A null or {@link CopilotTokenResolver#SHARED} partition yields the unpartitioned ID. */
    static String sessionId(CopilotAgentDefinition definition, String conversationId, String partition) {
        try {
            String identity0 = definition.typeName() + "\0" + definition.name() + "\0" + conversationId;
            if (partition != null && !partition.isEmpty()) {
                identity0 += "\0" + partition;
            }
            byte[] identity = identity0.getBytes(StandardCharsets.UTF_8);
            return "agent-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Required SHA-256 digest is unavailable", ex);
        }
    }

    /** Returns a point-in-time, read-only snapshot of invocation capacity for diagnostics. */
    public ExecutionStats executionStats() {
        int accepted = Math.max(0, maxOutstanding - outstanding.availablePermits());
        int running = Math.min(accepted, Math.max(0, maxConcurrent - concurrent.availablePermits()));
        return new ExecutionStats(running, accepted - running, maxConcurrent, maxOutstanding);
    }

    /**
     * @param running invocations currently holding an execution permit
     * @param queued accepted invocations waiting for an execution permit or a prior turn
     * @param maxConcurrent configured {@code max-concurrent-invocations}
     * @param maxOutstanding configured {@code max-outstanding-invocations}
     */
    public record ExecutionStats(int running, int queued, int maxConcurrent, int maxOutstanding) {
    }

    @Override
    public void close() {
        synchronized (pending) {
            closed = true;
        }
        executor.close();
    }
}
