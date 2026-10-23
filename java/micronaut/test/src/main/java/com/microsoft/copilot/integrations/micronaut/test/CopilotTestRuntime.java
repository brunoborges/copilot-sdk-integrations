package com.microsoft.copilot.integrations.micronaut.test;

import java.io.Closeable;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.copilot.ConnectionState;
import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SessionMetadata;
import com.github.copilot.rpc.ToolDefinition;
import com.github.copilot.rpc.ToolInvocation;

import com.microsoft.copilot.integrations.core.CopilotAgentTelemetry;

/**
 * An in-memory, scripted Copilot runtime for tests.
 *
 * <p>{@link #client()} returns a {@link CopilotClient} that never starts the Copilot CLI. Sessions are persisted in
 * memory so resuming a conversation works, prompts are answered from per-agent {@link CopilotAgentScript scripts},
 * scripted tool calls invoke the real tool handlers registered with the session, and every interaction is recorded
 * for assertions.
 *
 * <pre>{@code
 * runtime.agent("advisor").replies("Use constructor injection.");
 * assertThat(advisor.ask("42", "How should I inject beans?").join()).isEqualTo("Use constructor injection.");
 * assertThat(runtime.prompts("advisor")).containsExactly("How should I inject beans?");
 * }</pre>
 *
 * <h2>Agent detection</h2>
 * Copilot agents are identified through {@link #telemetry(CopilotAgentTelemetry)}, which
 * {@link CopilotTestRuntimeFactory} installs automatically. Calls made outside an agent (for example directly
 * on the client) use the {@link #anyAgent()} script, unless wrapped in {@link #actAs(String)}.
 *
 * <h2>Supported client operations</h2>
 * The client supports {@code start}, {@code stop}, {@code forceStop}, {@code close}, {@code getState},
 * {@code createSession}, {@code resumeSession}, {@code getSessionMetadata}, {@code listSessions()},
 * {@code deleteSession} and {@code getLastSessionId}. Sessions support {@code getSessionId}, {@code on},
 * {@code send}, all {@code sendAndWait} overloads (including typed structured output), {@code abort},
 * {@code setTools}, {@code getMessages}, {@code setEventErrorHandler}, {@code setEventErrorPolicy} and
 * {@code close}. Any other operation fails with an {@link UnsupportedOperationException} naming the method.
 *
 * <p>This class is thread-safe.
 */
public final class CopilotTestRuntime {

    private static final System.Logger log = System.getLogger(CopilotTestRuntime.class.getName());

    private static final long DEFAULT_TIMEOUT_MS = 60_000;

    private final Map<String, CopilotAgentScript> scripts = new ConcurrentHashMap<>();

    private final CopilotAgentScript anyAgent = new CopilotAgentScript(null);

    private final Map<String, SessionState> sessions = new LinkedHashMap<>();

    private final List<CopilotTestPrompt> prompts = new CopyOnWriteArrayList<>();

    private final List<CopilotTestToolCall> toolCalls = new CopyOnWriteArrayList<>();

    private final List<String> aborts = new CopyOnWriteArrayList<>();

    private final Set<FakeSession> openSessions = ConcurrentHashMap.newKeySet();

    private final ThreadLocal<String> currentAgent = new ThreadLocal<>();

    private final CopilotClient client;

    private volatile String lastSessionId;

    public CopilotTestRuntime() {
        this.client = Mockito.mock(CopilotClient.class,
                Mockito.withSettings().stubOnly().defaultAnswer(this::clientCall).name("copilotTestClient"));
    }

    /** The scripted {@link CopilotClient}. */
    public CopilotClient client() {
        return client;
    }

    // ---------------------------------------------------------------- scripting

    /** The script for the named agent ({@code @CopilotAgent} name). */
    public CopilotAgentScript agent(String name) {
        Objects.requireNonNull(name, "name");
        return scripts.computeIfAbsent(name, CopilotAgentScript::new);
    }

    /** The fallback script, used when the calling agent has no scripted turns or is unknown. */
    public CopilotAgentScript anyAgent() {
        return anyAgent;
    }

    /**
     * Attributes calls made on the current thread to the named agent until the returned scope is closed. Useful when
     * driving {@link #client()} directly.
     */
    public CopilotAgentTelemetry.Scope actAs(String agent) {
        String previous = currentAgent.get();
        currentAgent.set(agent);
        return () -> restore(previous);
    }

    /**
     * Wraps agent telemetry so the runtime can tell which Copilot agent issued each call. Installed automatically
     * by {@link CopilotTestRuntimeFactory}; when building a {@code CopilotAgentInvoker} by hand, pass the
     * wrapped telemetry to it.
     */
    public CopilotAgentTelemetry telemetry(CopilotAgentTelemetry delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new AgentTrackingTelemetry(delegate);
    }

    /** Agent-tracking telemetry with no other behavior. */
    public CopilotAgentTelemetry telemetry() {
        return telemetry(CopilotAgentTelemetry.NOOP);
    }

    // ---------------------------------------------------------------- recordings

    /** All prompts received, in order. */
    public List<CopilotTestPrompt> receivedPrompts() {
        return List.copyOf(prompts);
    }

    /** The texts of all prompts received, in order. */
    public List<String> prompts() {
        return prompts.stream().map(CopilotTestPrompt::prompt).toList();
    }

    /** The texts of the prompts received by the named agent, in order. */
    public List<String> prompts(String agent) {
        return prompts.stream().filter(p -> Objects.equals(agent, p.agent())).map(CopilotTestPrompt::prompt).toList();
    }

    /** All persisted sessions, in creation order. */
    public List<CopilotTestSession> sessions() {
        synchronized (sessions) {
            return sessions.values().stream().map(SessionState::snapshot).toList();
        }
    }

    /** The sessions created by the named agent, in creation order. */
    public List<CopilotTestSession> sessions(String agent) {
        return sessions().stream().filter(s -> Objects.equals(agent, s.agent())).toList();
    }

    /** The session with the given ID, or {@code null}. */
    public CopilotTestSession session(String sessionId) {
        synchronized (sessions) {
            var state = sessions.get(sessionId);
            return state == null ? null : state.snapshot();
        }
    }

    /**
     * The most recently created session of the named agent.
     *
     * @throws IllegalStateException if the agent has not created a session
     */
    public CopilotTestSession lastSession(String agent) {
        var matching = sessions(agent);
        if (matching.isEmpty()) {
            throw new IllegalStateException("Agent '" + agent + "' has not created a Copilot session");
        }
        return matching.get(matching.size() - 1);
    }

    /** The configuration used to create the most recent session of the named agent. */
    public SessionConfig sessionConfig(String agent) {
        return lastSession(agent).config();
    }

    /** All tool calls performed by scripted turns, in order. */
    public List<CopilotTestToolCall> toolCalls() {
        return List.copyOf(toolCalls);
    }

    /** The tool calls performed on behalf of the named agent, in order. */
    public List<CopilotTestToolCall> toolCalls(String agent) {
        return toolCalls.stream().filter(c -> Objects.equals(agent, c.agent())).toList();
    }

    /** The IDs of the sessions that received {@code abort()}, in order. */
    public List<String> aborts() {
        return List.copyOf(aborts);
    }

    /** Forgets all scripts, recordings and persisted sessions, and aborts turns still in flight. */
    public void reset() {
        for (var session : openSessions) {
            session.abortTurns(false);
        }
        openSessions.clear();
        scripts.clear();
        anyAgent.clear();
        synchronized (sessions) {
            sessions.clear();
        }
        prompts.clear();
        toolCalls.clear();
        aborts.clear();
        lastSessionId = null;
    }

    // ---------------------------------------------------------------- client

    private Object clientCall(InvocationOnMock invocation) {
        Method method = invocation.getMethod();
        Object[] args = invocation.getArguments();
        return switch (signature(method)) {
            case "start()", "stop()", "forceStop()" -> CompletableFuture.completedFuture(null);
            case "close()" -> null;
            case "getState()" -> ConnectionState.CONNECTED;
            case "createSession(SessionConfig)" -> createSession((SessionConfig) args[0]);
            case "resumeSession(String,ResumeSessionConfig)" -> resumeSession((String) args[0],
                    (ResumeSessionConfig) args[1]);
            case "getSessionMetadata(String)" -> CompletableFuture.completedFuture(metadata((String) args[0]));
            case "listSessions()" -> CompletableFuture.completedFuture(listSessions());
            case "deleteSession(String)" -> deleteSession((String) args[0]);
            case "getLastSessionId()" -> CompletableFuture.completedFuture(lastSessionId);
            case "toString()" -> "CopilotTestRuntime client";
            case "hashCode()" -> System.identityHashCode(invocation.getMock());
            case "equals(Object)" -> invocation.getMock() == args[0];
            default -> throw unsupported("CopilotClient", method);
        };
    }

    private CompletableFuture<CopilotSession> createSession(SessionConfig config) {
        Objects.requireNonNull(config, "config");
        String id = config.getSessionId() == null ? UUID.randomUUID().toString() : config.getSessionId();
        var state = new SessionState(id, currentAgent.get(), config);
        synchronized (sessions) {
            if (sessions.putIfAbsent(id, state) != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Copilot session '" + id + "' already exists"));
            }
        }
        lastSessionId = id;
        return CompletableFuture.completedFuture(open(state));
    }

    private CompletableFuture<CopilotSession> resumeSession(String id, ResumeSessionConfig config) {
        SessionState state;
        synchronized (sessions) {
            state = sessions.get(id);
        }
        if (state == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Copilot session not found: " + id));
        }
        state.resume(config, currentAgent.get());
        lastSessionId = id;
        return CompletableFuture.completedFuture(open(state));
    }

    private SessionMetadata metadata(String id) {
        synchronized (sessions) {
            var state = sessions.get(id);
            return state == null ? null : state.metadata();
        }
    }

    private List<SessionMetadata> listSessions() {
        synchronized (sessions) {
            return sessions.values().stream().map(SessionState::metadata).toList();
        }
    }

    private CompletableFuture<Void> deleteSession(String id) {
        synchronized (sessions) {
            sessions.remove(id);
        }
        return CompletableFuture.completedFuture(null);
    }

    private CopilotSession open(SessionState state) {
        var session = new FakeSession(state, currentAgent.get() != null ? currentAgent.get() : state.agent);
        session.mock = Mockito.mock(CopilotSession.class,
                Mockito.withSettings().stubOnly().defaultAnswer(session::call).name("copilotTestSession"));
        openSessions.add(session);
        return session.mock;
    }

    private CopilotAgentScript.Turn nextTurn(String agent) {
        var script = agent == null ? null : scripts.get(agent);
        var turn = script == null ? null : script.next();
        if (turn == null) {
            turn = anyAgent.next();
        }
        if (turn == null) {
            throw new IllegalStateException(agent == null
                    ? "No scripted reply; use runtime.anyAgent().replies(...)"
                    : "No scripted reply for agent '" + agent + "'; use runtime.agent(\"" + agent
                            + "\").replies(...) or runtime.anyAgent().replies(...)");
        }
        return turn;
    }

    private void restore(String previous) {
        if (previous == null) {
            currentAgent.remove();
        }
        else {
            currentAgent.set(previous);
        }
    }

    private static String signature(Method method) {
        return method.getName() + Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName)
                .collect(Collectors.joining(",", "(", ")"));
    }

    private static UnsupportedOperationException unsupported(String type, Method method) {
        return new UnsupportedOperationException(type + "." + signature(method)
                + " is not supported by CopilotTestRuntime; see the CopilotTestRuntime javadoc for supported operations");
    }

    private static String text(Object value) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        try {
            return CopilotTestEvents.MAPPER.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    // ---------------------------------------------------------------- state

    private static final class SessionState {

        final String id;

        volatile String agent;

        final SessionConfig config;

        final Instant created = Instant.now();

        volatile Instant modified = created;

        final List<ResumeSessionConfig> resumes = new CopyOnWriteArrayList<>();

        final List<String> prompts = new CopyOnWriteArrayList<>();

        final List<SessionEvent> messages = new CopyOnWriteArrayList<>();

        volatile List<ToolDefinition> toolsOverride;

        SessionState(String id, String agent, SessionConfig config) {
            this.id = id;
            this.agent = agent;
            this.config = config;
        }

        void resume(ResumeSessionConfig resume, String caller) {
            resumes.add(resume == null ? new ResumeSessionConfig() : resume);
            toolsOverride = null;
            if (agent == null) {
                agent = caller;
            }
            modified = Instant.now();
        }

        List<ToolDefinition> tools() {
            if (toolsOverride != null) {
                return toolsOverride;
            }
            var tools = resumes.isEmpty() ? config.getTools() : resumes.get(resumes.size() - 1).getTools();
            return tools == null ? List.of() : tools;
        }

        SessionMetadata metadata() {
            var metadata = new SessionMetadata();
            metadata.setSessionId(id);
            metadata.setStartTime(created.toString());
            metadata.setModifiedTime(modified.toString());
            return metadata;
        }

        CopilotTestSession snapshot() {
            return new CopilotTestSession(id, agent, config, List.copyOf(resumes), List.copyOf(prompts));
        }
    }

    private final class FakeSession {

        final SessionState state;

        final String agent;

        final List<Consumer<SessionEvent>> listeners = new CopyOnWriteArrayList<>();

        final Set<ActiveTurn> active = ConcurrentHashMap.newKeySet();

        volatile boolean closed;

        CopilotSession mock;

        FakeSession(SessionState state, String agent) {
            this.state = state;
            this.agent = agent;
        }

        Object call(InvocationOnMock invocation) {
            Method method = invocation.getMethod();
            Object[] args = invocation.getArguments();
            return switch (signature(method)) {
                case "getSessionId()" -> state.id;
                case "on(Consumer)" -> subscribe(cast(args[0]));
                case "on(Class,Consumer)" -> subscribe((Class<?>) args[0], cast(args[1]));
                case "send(String)" -> send(new MessageOptions().setPrompt((String) args[0]));
                case "send(MessageOptions)" -> send((MessageOptions) args[0]);
                case "sendAndWait(String)" -> sendAndWait(new MessageOptions().setPrompt((String) args[0]),
                        DEFAULT_TIMEOUT_MS, null);
                case "sendAndWait(MessageOptions)" -> sendAndWait((MessageOptions) args[0], DEFAULT_TIMEOUT_MS, null);
                case "sendAndWait(MessageOptions,long)" -> sendAndWait((MessageOptions) args[0], (Long) args[1], null);
                case "sendAndWait(String,Class)" -> typed(new MessageOptions().setPrompt((String) args[0]),
                        (Class<?>) args[1], DEFAULT_TIMEOUT_MS);
                case "sendAndWait(MessageOptions,Class)" -> typed((MessageOptions) args[0], (Class<?>) args[1],
                        DEFAULT_TIMEOUT_MS);
                case "sendAndWait(MessageOptions,Class,long)" -> typed((MessageOptions) args[0], (Class<?>) args[1],
                        (Long) args[2]);
                case "abort()" -> abort();
                case "setTools(List)" -> setTools(cast(args[0]));
                case "getMessages()" -> CompletableFuture.completedFuture(List.copyOf(state.messages));
                case "setEventErrorHandler(EventErrorHandler)", "setEventErrorPolicy(EventErrorPolicy)" -> null;
                case "close()" -> close();
                case "toString()" -> "CopilotTestRuntime session " + state.id;
                case "hashCode()" -> System.identityHashCode(invocation.getMock());
                case "equals(Object)" -> invocation.getMock() == args[0];
                default -> throw unsupported("CopilotSession", method);
            };
        }

        @SuppressWarnings("unchecked")
        private static <T> T cast(Object value) {
            return (T) value;
        }

        private Closeable subscribe(Consumer<SessionEvent> listener) {
            Objects.requireNonNull(listener, "listener");
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        private Closeable subscribe(Class<?> type, Consumer<Object> listener) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(listener, "listener");
            return subscribe(event -> {
                if (type.isInstance(event)) {
                    listener.accept(event);
                }
            });
        }

        private CompletableFuture<String> send(MessageOptions options) {
            var messageId = UUID.randomUUID().toString();
            return sendAndWait(options, 0, null).handle((reply, error) -> messageId);
        }

        private CompletableFuture<Object> typed(MessageOptions options, Class<?> type, long timeoutMs) {
            Objects.requireNonNull(type, "responseType");
            return sendAndWait(options, timeoutMs, type).thenApply(reply -> {
                try {
                    Object value = CopilotTestEvents.MAPPER.readValue(reply.getData().content(), type);
                    if (value == null) {
                        throw new IllegalStateException("Structured response was JSON null, not a result");
                    }
                    return value;
                }
                catch (JsonProcessingException ex) {
                    throw new IllegalStateException("Scripted reply for structured output is not a valid "
                            + type.getName() + " JSON document: " + reply.getData().content(), ex);
                }
            });
        }

        private CompletableFuture<AssistantMessageEvent> sendAndWait(MessageOptions options, long timeoutMs,
                Class<?> responseType) {
            Objects.requireNonNull(options, "options");
            if (closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Copilot session '" + state.id + "' is closed"));
            }
            String caller = currentAgent.get() != null ? currentAgent.get() : agent;
            String prompt = options.getPrompt();
            prompts.add(new CopilotTestPrompt(caller, state.id, prompt, options, responseType));
            state.prompts.add(prompt);
            state.modified = Instant.now();
            CopilotAgentScript.Turn turn;
            try {
                turn = nextTurn(caller);
            }
            catch (IllegalStateException ex) {
                return CompletableFuture.failedFuture(ex);
            }
            var result = new CompletableFuture<AssistantMessageEvent>();
            var running = new ActiveTurn(result);
            var worker = Thread.ofVirtual().name("copilot-test-turn-" + state.id)
                    .unstarted(() -> run(turn, caller, prompt, result));
            running.worker = worker;
            active.add(running);
            result.whenComplete((reply, error) -> {
                active.remove(running);
                if (result.isCancelled()) {
                    worker.interrupt();
                }
            });
            if (timeoutMs > 0) {
                CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS).execute(() -> {
                    if (result.completeExceptionally(
                            new TimeoutException("sendAndWait timed out after " + timeoutMs + "ms"))) {
                        worker.interrupt();
                    }
                });
            }
            dispatch(CopilotTestEvents.userMessage(prompt));
            worker.start();
            return result;
        }

        private void run(CopilotAgentScript.Turn turn, String caller, String prompt,
                CompletableFuture<AssistantMessageEvent> result) {
            try {
                var calls = new ArrayList<CopilotTestToolCall>();
                for (var step : turn.steps()) {
                    if (result.isDone()) {
                        return;
                    }
                    switch (step) {
                        case CopilotAgentScript.EmitStep emit -> emit.events().forEach(this::dispatch);
                        case CopilotAgentScript.DelayStep delay -> Thread.sleep(delay.delay());
                        case CopilotAgentScript.ToolStep tool -> calls.add(callTool(caller, tool));
                    }
                }
                if (result.isDone()) {
                    return;
                }
                if (turn.failure() != null) {
                    var failure = turn.failure();
                    dispatch(CopilotTestEvents.error(failure.getClass().getSimpleName(), failure.getMessage()));
                    result.completeExceptionally(failure);
                    return;
                }
                String content = turn.reply().apply(new CopilotTestTurn(caller, state.id, prompt, List.copyOf(calls)));
                if (result.isDone()) {
                    return;
                }
                var message = CopilotTestEvents.assistantMessage(content);
                dispatch(message);
                dispatch(CopilotTestEvents.idle(false));
                result.complete(message);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            catch (Throwable ex) {
                result.completeExceptionally(ex);
            }
        }

        private CopilotTestToolCall callTool(String caller, CopilotAgentScript.ToolStep step)
                throws InterruptedException {
            var tools = state.tools();
            var tool = tools.stream().filter(t -> matches(t, step.toolName())).findFirst().orElseThrow(
                    () -> new IllegalStateException("Scripted tool call '" + step.toolName()
                            + "' does not match a tool registered with Copilot session '" + state.id + "'"
                            + (caller == null ? "" : " of agent '" + caller + "'") + "; registered tools: "
                            + tools.stream().map(ToolDefinition::name).toList()));
            String callId = UUID.randomUUID().toString();
            dispatch(CopilotTestEvents.toolStart(callId, step.toolName(), step.arguments()));
            var invocation = new ToolInvocation().setSessionId(state.id).setToolCallId(callId)
                    .setToolName(tool.name()).setArguments(CopilotTestEvents.MAPPER.valueToTree(step.arguments()));
            Object value = null;
            Throwable failure = null;
            try {
                value = tool.handler().invoke(invocation).get();
            }
            catch (ExecutionException ex) {
                failure = ex.getCause() == null ? ex : ex.getCause();
            }
            catch (RuntimeException ex) {
                failure = ex;
            }
            var call = new CopilotTestToolCall(caller, state.id, callId, step.toolName(), step.arguments(), value,
                    failure);
            toolCalls.add(call);
            dispatch(CopilotTestEvents.toolComplete(callId, failure == null ? text(value) : null,
                    failure == null ? null : String.valueOf(failure.getMessage())));
            return call;
        }

        private static boolean matches(ToolDefinition tool, String name) {
            return name.equals(tool.name())
                    || (tool.namespace() != null && name.equals(tool.namespace().name() + "." + tool.name()));
        }

        private void dispatch(SessionEvent event) {
            state.messages.add(event);
            for (var listener : listeners) {
                try {
                    listener.accept(event);
                }
                catch (RuntimeException ex) {
                    log.log(System.Logger.Level.WARNING, "Copilot session event listener failed", ex);
                }
            }
        }

        private CompletableFuture<Void> abort() {
            aborts.add(state.id);
            abortTurns(true);
            return CompletableFuture.completedFuture(null);
        }

        void abortTurns(boolean notify) {
            for (var running : active) {
                running.worker.interrupt();
                if (running.result.complete(null) && notify) {
                    dispatch(CopilotTestEvents.idle(true));
                }
            }
        }

        private CompletableFuture<Void> setTools(List<ToolDefinition> tools) {
            state.toolsOverride = tools == null ? List.of() : List.copyOf(tools);
            return CompletableFuture.completedFuture(null);
        }

        private Object close() {
            closed = true;
            listeners.clear();
            openSessions.remove(this);
            return null;
        }
    }

    private static final class ActiveTurn {

        final CompletableFuture<AssistantMessageEvent> result;

        volatile Thread worker;

        ActiveTurn(CompletableFuture<AssistantMessageEvent> result) {
            this.result = result;
        }
    }

    private final class AgentTrackingTelemetry implements CopilotAgentTelemetry {

        private final CopilotAgentTelemetry delegate;

        AgentTrackingTelemetry(CopilotAgentTelemetry delegate) {
            this.delegate = delegate;
        }

        @Override
        public Call start(String agent, String model) {
            var call = delegate.start(agent, model);
            return new Call() {

                @Override
                public Scope openScope() {
                    var scope = actAs(agent);
                    try {
                        var inner = call.openScope();
                        return () -> {
                            try {
                                inner.close();
                            }
                            finally {
                                scope.close();
                            }
                        };
                    }
                    catch (RuntimeException ex) {
                        scope.close();
                        throw ex;
                    }
                }

                @Override
                public Call tool(String name) {
                    return call.tool(name);
                }

                @Override
                public void usage(com.github.copilot.generated.AssistantUsageEvent event) {
                    call.usage(event);
                }

                @Override
                public void error(Throwable error) {
                    call.error(error);
                }

                @Override
                public void cancelled() {
                    call.cancelled();
                }

                @Override
                public void stop() {
                    call.stop();
                }
            };
        }

        @Override
        public void rejected(String agent) {
            delegate.rejected(agent);
        }
    }
}
