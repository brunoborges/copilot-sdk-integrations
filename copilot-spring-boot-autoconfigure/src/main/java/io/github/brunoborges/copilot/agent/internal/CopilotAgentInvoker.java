package io.github.brunoborges.copilot.agent.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.ToolDefinition;
import com.github.copilot.generated.AssistantUsageEvent;
import io.github.brunoborges.copilot.agent.CopilotAgentContext;
import io.github.brunoborges.copilot.agent.CopilotAgentTelemetry;
import io.github.brunoborges.copilot.agent.CopilotAgentEvent;
import io.github.brunoborges.copilot.agent.CopilotConversationNotFoundException;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentExecutionProperties;
import org.springframework.util.Assert;

public final class CopilotAgentInvoker implements AutoCloseable {

    private static final Logger log = Logger.getLogger(CopilotAgentInvoker.class.getName());
    private final CopilotClient client;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CompletableFuture<Void>> pending = new HashMap<>();
    private final CopilotAgentTelemetry telemetry;
    private final Semaphore concurrent;
    private final Semaphore outstanding;
    private volatile boolean closed;

    public CopilotAgentInvoker(CopilotClient client) {
        this(client, CopilotAgentTelemetry.NOOP, new CopilotAgentExecutionProperties(8, 64));
    }

    public CopilotAgentInvoker(CopilotClient client, CopilotAgentTelemetry telemetry,
            CopilotAgentExecutionProperties execution) {
        this.client = client;
        this.telemetry = telemetry;
        concurrent = new Semaphore(execution.maxConcurrentInvocations(), true);
        outstanding = new Semaphore(execution.maxOutstandingInvocations());
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
        return invokeOwned(definition, conversationId, message, events, CopilotAgentLifetime.Lease.NONE);
    }

    public CompletableFuture<String> invokeBound(CopilotAgentDefinition definition, String conversationId, String message,
            Consumer<CopilotAgentEvent> events, CopilotAgentLifetime lifetime) {
        CopilotAgentLifetime.Lease owner;
        try {
            owner = lifetime.acquire();
        } catch (IllegalStateException ex) {
            return CompletableFuture.failedFuture(ex);
        }
        try (var scope = owner.enter()) {
            return invokeOwned(definition, conversationId, message, events, owner);
        }
    }

    private CompletableFuture<String> invokeOwned(CopilotAgentDefinition definition, String conversationId, String message,
            Consumer<CopilotAgentEvent> events, CopilotAgentLifetime.Lease owner) {
        boolean transferred = false;
        try {
            if (conversationId == null || conversationId.isBlank() || message == null || message.isBlank()) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Conversation identity and @UserMessage must be non-blank strings"));
            }
            String sessionId = sessionId(definition, conversationId);
            var context = new CopilotAgentContext(definition.name(), conversationId, sessionId);
            var result = new CompletableFuture<String>();
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
                                        observation, subscriber, owner));
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

    private void execute(CopilotAgentDefinition definition, CopilotAgentContext context, String message,
            CompletableFuture<String> result, CompletableFuture<Void> finished, CopilotAgentTelemetry.Call observation,
            Consumer<CopilotAgentEvent> events, CopilotAgentLifetime.Lease owner) {
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
                        response = exchange(definition, context, message, observation, events, owner);
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
            try {
                failure = finishObservation(observation, failure, result.isCancelled());
                if (acquired) {
                    concurrent.release();
                }
                outstanding.release();
                finish(context.sessionId(), finished);
            } finally {
                owner.close();
                instanceScope.close();
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
            CopilotAgentLifetime.Lease owner) throws IOException {
        var observerFailure = new AtomicReference<RuntimeException>();
        String content;
        try (var session = open(definition, context, observation, events != null, owner);
                var subscription = session.on(event -> {
                    try (var callbackScope = owner.enter()) {
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
            var reply = session.sendAndWait(new MessageOptions().setPrompt(message),
                    definition.responseTimeout().toMillis()).join();
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

    private CopilotSession open(CopilotAgentDefinition definition, CopilotAgentContext context,
            CopilotAgentTelemetry.Call observation, boolean streaming, CopilotAgentLifetime.Lease owner) {
        // Null is the SDK's documented not-found result; lookup errors must not create a new conversation.
        if (client.getSessionMetadata(context.sessionId()).join() == null) {
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
            definition.customizers().forEach(customizer -> customizer.customize(context, config));
            if (streaming) {
                config.setStreaming(true);
            }
            config.setTools(observedTools(config.getTools(), observation, owner));
            Assert.isTrue(context.sessionId().equals(config.getSessionId()), "Agent customizers must not change the session ID");
            Assert.notNull(config.getOnPermissionRequest(), "Agent customizers must not remove the permission handler");
            config.setOnPermissionRequest(ownedPermissions(config.getOnPermissionRequest(), owner));
            var session = client.createSession(config).join();
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
        definition.customizers().forEach(customizer -> customizer.customize(context, config));
        if (streaming) {
            config.setStreaming(true);
        }
        config.setTools(observedTools(config.getTools(), observation, owner));
        Assert.notNull(config.getOnPermissionRequest(), "Agent customizers must not remove the permission handler");
        config.setOnPermissionRequest(ownedPermissions(config.getOnPermissionRequest(), owner));
        var session = client.resumeSession(context.sessionId(), config).join();
        owner.sessionOpened();
        return session;
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
        try {
            byte[] identity = (definition.typeName() + "\0" + definition.name() + "\0" + conversationId)
                    .getBytes(StandardCharsets.UTF_8);
            return "agent-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Required SHA-256 digest is unavailable", ex);
        }
    }

    @Override
    public void close() {
        synchronized (pending) {
            closed = true;
        }
        executor.close();
    }
}
