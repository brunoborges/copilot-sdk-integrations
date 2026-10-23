package io.github.brunoborges.copilot.micronaut.test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.copilot.generated.SessionEvent;

/**
 * Scripted model behavior for one agent of a {@link CopilotTestRuntime}.
 *
 * <p>A script is a queue of <em>turns</em>; each prompt sent to the agent consumes the next turn, and the last turn
 * is repeated once the queue is exhausted. A turn consists of optional preparatory steps followed by exactly one
 * terminal step:
 * <ul>
 * <li>Preparatory steps run in declaration order: {@link #invokesTool(String, Map)}, {@link #emits(SessionEvent...)},
 * {@link #streams(String...)} and {@link #delays(Duration)}.</li>
 * <li>Terminal steps complete the turn and queue it: {@link #replies(String)}, {@link #repliesWith(Function)},
 * {@link #repliesUsing(Function)}, {@link #replyJson(Object)}, {@link #streamsReply(String...)} and
 * {@link #fails(Throwable)}.</li>
 * </ul>
 *
 * <pre>{@code
 * runtime.agent("advisor")
 *         .invokesTool("describe_term", Map.of("term", "bean"))
 *         .repliesUsing(turn -> "A bean is " + turn.toolResult("describe_term"))
 *         .replies("Anything else?");
 * }</pre>
 *
 * <p>Scripts are thread-safe.
 */
public final class CopilotAgentScript {

    private final String agent;

    private final Deque<Turn> turns = new ArrayDeque<>();

    private List<Step> pending = new ArrayList<>();

    CopilotAgentScript(String agent) {
        this.agent = agent;
    }

    /** The agent name this script applies to, or {@code null} for the fallback script. */
    public String agent() {
        return agent;
    }

    /**
     * Makes the model call a registered tool with the given arguments before replying. The tool's handler, for
     * example a CDI {@code @CopilotTool} method, runs for real; its result is recorded and available to
     * {@link #repliesUsing(Function)}. Tool permission handlers are not consulted.
     */
    public CopilotAgentScript invokesTool(String toolName, Map<String, ?> arguments) {
        Objects.requireNonNull(toolName, "toolName");
        return step(new ToolStep(toolName, arguments == null ? Map.of() : new LinkedHashMap<>(arguments)));
    }

    /** Makes the model call a registered tool without arguments before replying. */
    public CopilotAgentScript invokesTool(String toolName) {
        return invokesTool(toolName, Map.of());
    }

    /** Delivers the given session events to session listeners before replying. */
    public CopilotAgentScript emits(SessionEvent... events) {
        return step(new EmitStep(List.of(events)));
    }

    /** Emits one {@code assistant.message_delta} event per chunk before replying. */
    public CopilotAgentScript streams(String... chunks) {
        return emits(Arrays.stream(chunks).map(CopilotTestEvents::messageDelta).toArray(SessionEvent[]::new));
    }

    /** Pauses the turn, for example to exercise timeouts or cancellation. */
    public CopilotAgentScript delays(Duration delay) {
        Objects.requireNonNull(delay, "delay");
        return step(new DelayStep(delay));
    }

    /** Ends the turn with a fixed assistant reply. */
    public CopilotAgentScript replies(String reply) {
        Objects.requireNonNull(reply, "reply");
        return terminal(turn -> reply);
    }

    /** Ends the turn with a reply computed from the prompt. */
    public CopilotAgentScript repliesWith(Function<String, String> reply) {
        Objects.requireNonNull(reply, "reply");
        return terminal(turn -> reply.apply(turn.prompt()));
    }

    /** Ends the turn with a reply computed from the full turn context, including tool results. */
    public CopilotAgentScript repliesUsing(Function<CopilotTestTurn, String> reply) {
        return terminal(Objects.requireNonNull(reply, "reply"));
    }

    /**
     * Ends the turn with a structured (JSON) reply: the value is serialized with Jackson and returned as the
     * assistant text, which typed {@code sendAndWait(..., Class)} calls deserialize again.
     */
    public CopilotAgentScript replyJson(Object value) {
        String json;
        try {
            json = CopilotTestEvents.MAPPER.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Cannot serialize scripted JSON reply", ex);
        }
        return replies(json);
    }

    /** Streams the chunks as {@code assistant.message_delta} events and ends the turn with their concatenation. */
    public CopilotAgentScript streamsReply(String... chunks) {
        streams(chunks);
        return replies(String.join("", chunks));
    }

    /** Ends the turn with a failure: a {@code session.error} event is emitted and the send fails with the error. */
    public CopilotAgentScript fails(Throwable error) {
        Objects.requireNonNull(error, "error");
        synchronized (this) {
            turns.add(new Turn(List.copyOf(pending), null, error));
            pending = new ArrayList<>();
        }
        return this;
    }

    /** Removes all scripted turns. */
    public synchronized CopilotAgentScript clear() {
        turns.clear();
        pending = new ArrayList<>();
        return this;
    }

    private synchronized CopilotAgentScript step(Step step) {
        pending.add(step);
        return this;
    }

    private synchronized CopilotAgentScript terminal(Function<CopilotTestTurn, String> reply) {
        turns.add(new Turn(List.copyOf(pending), reply, null));
        pending = new ArrayList<>();
        return this;
    }

    synchronized Turn next() {
        if (turns.size() > 1) {
            return turns.poll();
        }
        return turns.peek();
    }

    record Turn(List<Step> steps, Function<CopilotTestTurn, String> reply, Throwable failure) {
    }

    sealed interface Step permits ToolStep, EmitStep, DelayStep {
    }

    record ToolStep(String toolName, Map<String, Object> arguments) implements Step {
    }

    record EmitStep(List<SessionEvent> events) implements Step {
    }

    record DelayStep(Duration delay) implements Step {
    }
}
