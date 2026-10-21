package io.github.brunoborges.copilot.agent.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import io.github.brunoborges.copilot.agent.CopilotAgentContext;
import org.springframework.util.Assert;

public final class CopilotAgentInvoker implements AutoCloseable {

    private static final Logger log = Logger.getLogger(CopilotAgentInvoker.class.getName());
    private final CopilotClient client;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CompletableFuture<Void>> pending = new HashMap<>();
    private boolean closed;

    public CopilotAgentInvoker(CopilotClient client) {
        this.client = client;
    }

    public CompletableFuture<String> invoke(CopilotAgentDefinition definition, String conversationId, String message) {
        if (conversationId == null || conversationId.isBlank() || message == null || message.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("@ConversationId and @UserMessage must be non-blank strings"));
        }
        String sessionId = sessionId(definition, conversationId);
        var context = new CopilotAgentContext(definition.name(), conversationId, sessionId);
        var result = new CompletableFuture<String>();
        var finished = new CompletableFuture<Void>();
        synchronized (pending) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Copilot agent infrastructure is closed"));
            }
            var previous = pending.put(sessionId, finished);
            (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous)
                    .whenComplete((ignored, error) -> {
                        try {
                            executor.execute(() -> execute(definition, context, message, result, finished));
                        } catch (RejectedExecutionException ex) {
                            finish(sessionId, finished);
                            result.completeExceptionally(ex);
                        }
                    });
        }
        return result;
    }

    private void execute(CopilotAgentDefinition definition, CopilotAgentContext context, String message,
            CompletableFuture<String> result, CompletableFuture<Void> finished) {
        String response = null;
        Exception failure = null;
        try {
            response = exchange(definition, context, message);
        } catch (RuntimeException | IOException ex) {
            failure = ex;
        } catch (Error ex) {
            result.completeExceptionally(ex);
            throw ex;
        } finally {
            finish(context.sessionId(), finished);
        }
        if (failure == null) {
            result.complete(response);
        } else if (!result.completeExceptionally(failure)) {
            log.log(Level.WARNING, "Copilot agent '" + definition.name()
                    + "' failed after its result was already completed or cancelled", failure);
        }
    }

    private String exchange(CopilotAgentDefinition definition, CopilotAgentContext context, String message) throws IOException {
        var observerFailure = new AtomicReference<RuntimeException>();
        String content;
        try (var session = open(definition, context);
                var subscription = session.on(event -> {
                    for (var listener : definition.listeners()) {
                        try {
                            listener.onEvent(context, event);
                        } catch (RuntimeException ex) {
                            log.log(Level.SEVERE, "Copilot agent '" + definition.name() + "' listener failed", ex);
                            observerFailure.compareAndSet(null, ex);
                        }
                    }
                })) {
            Assert.isTrue(context.sessionId().equals(session.getSessionId()),
                    "Copilot runtime did not preserve the requested agent session ID");
            var reply = session.sendAndWait(new MessageOptions().setPrompt(message),
                    definition.responseTimeout().toMillis()).join();
            Assert.state(reply != null && reply.getData() != null && reply.getData().content() != null,
                    "Copilot agent completed without an assistant text response");
            content = reply.getData().content();
        }
        if (observerFailure.get() != null) {
            throw new IllegalStateException("Copilot agent listener failed", observerFailure.get());
        }
        return content;
    }

    private CopilotSession open(CopilotAgentDefinition definition, CopilotAgentContext context) {
        // Null is the SDK's documented not-found result; lookup errors must not create a new conversation.
        if (client.getSessionMetadata(context.sessionId()).join() == null) {
            var config = new SessionConfig().setSessionId(context.sessionId())
                    .setOnPermissionRequest(definition.permissions());
            if (!definition.model().isEmpty()) {
                config.setModel(definition.model());
            }
            if (!definition.reasoningEffort().isEmpty()) {
                config.setReasoningEffort(definition.reasoningEffort());
            }
            config.setSystemMessage(definition.systemMessage()).setTools(definition.tools());
            definition.customizers().forEach(customizer -> customizer.customize(context, config));
            Assert.isTrue(context.sessionId().equals(config.getSessionId()), "Agent customizers must not change the session ID");
            Assert.notNull(config.getOnPermissionRequest(), "Agent customizers must not remove the permission handler");
            return client.createSession(config).join();
        }
        var config = new ResumeSessionConfig().setOnPermissionRequest(definition.permissions());
        if (!definition.model().isEmpty()) {
            config.setModel(definition.model());
        }
        if (!definition.reasoningEffort().isEmpty()) {
            config.setReasoningEffort(definition.reasoningEffort());
        }
        config.setSystemMessage(definition.systemMessage()).setTools(definition.tools());
        definition.customizers().forEach(customizer -> customizer.customize(context, config));
        Assert.notNull(config.getOnPermissionRequest(), "Agent customizers must not remove the permission handler");
        return client.resumeSession(context.sessionId(), config).join();
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
