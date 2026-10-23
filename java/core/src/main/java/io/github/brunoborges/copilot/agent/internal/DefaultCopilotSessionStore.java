package io.github.brunoborges.copilot.agent.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.UserMessageEvent;
import com.github.copilot.rpc.SessionMetadata;
import io.github.brunoborges.copilot.agent.CopilotSessionMessage;
import io.github.brunoborges.copilot.agent.CopilotSessionStore;

/** Default {@link CopilotSessionStore} over the shared client and the invoker's per-session turn queue. */
public final class DefaultCopilotSessionStore implements CopilotSessionStore {

    /** Default bound on each runtime call, and on how long {@link #delete(String)} waits for a turn in flight. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private final CopilotSessionStorage storage;
    private final CopilotAgentInvoker invoker;
    private final Function<Class<?>, CopilotAgentDefinition> definitions;
    private final Duration timeout;

    /**
     * @param definitions returns the discovered definition of an agent type, or {@code null} when not discovered
     */
    public DefaultCopilotSessionStore(CopilotSessionStorage storage, CopilotAgentInvoker invoker,
            Function<Class<?>, CopilotAgentDefinition> definitions) {
        this(storage, invoker, definitions, DEFAULT_TIMEOUT);
    }

    public DefaultCopilotSessionStore(CopilotSessionStorage storage, CopilotAgentInvoker invoker,
            Function<Class<?>, CopilotAgentDefinition> definitions, Duration timeout) {
        Assert.notNull(storage, "Copilot session storage is required");
        Assert.notNull(invoker, "CopilotAgentInvoker is required");
        Assert.notNull(definitions, "Copilot agent definition lookup is required");
        Assert.isTrue(timeout != null && timeout.isPositive(), "Copilot session store timeout must be positive");
        this.storage = storage;
        this.invoker = invoker;
        this.definitions = definitions;
        this.timeout = timeout;
    }

    @Override
    public List<SessionMetadata> list() {
        List<SessionMetadata> sessions = await(storage.list());
        return (sessions == null ? List.<SessionMetadata>of() : sessions).stream()
                .filter(Objects::nonNull)
                .filter(DefaultCopilotSessionStore::managedLocal)
                .sorted(Comparator.comparing(DefaultCopilotSessionStore::modified,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Override
    public Optional<SessionMetadata> get(String sessionId) {
        Assert.hasText(sessionId, "Session ID must not be blank");
        return Optional.ofNullable(await(storage.get(sessionId)));
    }

    @Override
    public Optional<List<CopilotSessionMessage>> messages(String sessionId) {
        if (get(sessionId).isEmpty()) {
            return Optional.empty();
        }
        List<SessionEvent> events = await(storage.readEvents(sessionId));
        var messages = new ArrayList<CopilotSessionMessage>();
        for (var event : events == null ? List.<SessionEvent>of() : events) {
            if (event == null || (event.getAgentId() != null && !event.getAgentId().isEmpty())) {
                continue;
            }
            if (event instanceof UserMessageEvent user && user.getData() != null) {
                messages.add(new CopilotSessionMessage(CopilotSessionMessage.Role.USER, user.getData().content(),
                        user.getData().messageId(), event.getTimestamp()));
            } else if (event instanceof AssistantMessageEvent assistant && assistant.getData() != null
                    && assistant.getData().content() != null && !assistant.getData().content().isEmpty()) {
                messages.add(new CopilotSessionMessage(CopilotSessionMessage.Role.ASSISTANT,
                        assistant.getData().content(), assistant.getData().messageId(), event.getTimestamp()));
            }
        }
        return Optional.of(List.copyOf(messages));
    }

    @Override
    public boolean delete(String sessionId) {
        Assert.hasText(sessionId, "Session ID must not be blank");
        var abandoned = new AtomicBoolean();
        var deletion = invoker.exclusive(sessionId, () -> abandoned.get() ? CompletableFuture.completedFuture(false)
                : storage.get(sessionId).thenCompose(current -> current == null ? CompletableFuture.completedFuture(false)
                        : storage.delete(sessionId).thenApply(ignored -> true)));
        try {
            return Boolean.TRUE.equals(await(deletion));
        } catch (RuntimeException ex) {
            // A deletion still queued behind a turn must not run after the caller gave up on it.
            abandoned.set(true);
            throw ex;
        }
    }

    @Override
    public String sessionId(Class<?> agentType, String conversationId) {
        Assert.notNull(agentType, "Copilot agent type is required");
        var definition = definitions.apply(agentType);
        Assert.isTrue(definition != null, () -> "Copilot agent definition was not discovered: " + agentType.getName());
        return CopilotAgentInvoker.callerSessionId(definition, conversationId);
    }

    static boolean managedLocal(SessionMetadata session) {
        return !session.isRemote() && session.getSessionId() != null
                && session.getSessionId().startsWith(CopilotAgentInvoker.MANAGED_SESSION_PREFIX);
    }

    /** Parses the runtime's ISO-8601 modified time; {@code null} when absent or unparseable. */
    static Instant modified(SessionMetadata session) {
        String value = session.getModifiedTime();
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ex) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the Copilot runtime", ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("Copilot runtime did not respond within " + timeout, ex);
        } catch (ExecutionException ex) {
            var cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Copilot runtime call failed", cause);
        }
    }
}
