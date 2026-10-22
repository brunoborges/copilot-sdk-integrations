package io.github.brunoborges.copilot.quarkus.test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.AssistantUsageEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.generated.ToolExecutionCompleteEvent;
import com.github.copilot.generated.ToolExecutionStartEvent;
import com.github.copilot.generated.UserMessageEvent;

/**
 * Factories for Copilot SDK {@link SessionEvent session events}, used by {@link CopilotTestRuntime} and by tests
 * that script additional events with {@link CopilotAgentScript#emits(SessionEvent...)}.
 *
 * <p>Events are built from their JSON wire representation using the same Jackson configuration as the SDK, so any
 * event type known to the SDK can be produced with {@link #event(String, Map)}.
 */
public final class CopilotTestEvents {

    static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .setDefaultPropertyInclusion(
                    JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS));

    private CopilotTestEvents() {
    }

    /**
     * Builds an event from its wire {@code type} (for example {@code "assistant.intent"}) and {@code data} payload.
     */
    public static SessionEvent event(String type, Map<String, ?> data) {
        var json = new LinkedHashMap<String, Object>();
        json.put("type", type);
        json.put("data", data == null ? Map.of() : data);
        var event = MAPPER.convertValue(json, SessionEvent.class);
        event.setId(UUID.randomUUID());
        event.setTimestamp(OffsetDateTime.now());
        return event;
    }

    /** A streamed chunk of assistant text ({@code assistant.message_delta}). */
    public static AssistantMessageDeltaEvent messageDelta(String deltaContent) {
        return messageDelta(UUID.randomUUID().toString(), deltaContent);
    }

    /** A streamed chunk of assistant text belonging to the given message. */
    public static AssistantMessageDeltaEvent messageDelta(String messageId, String deltaContent) {
        return (AssistantMessageDeltaEvent) event("assistant.message_delta",
                Map.of("messageId", messageId, "deltaContent", deltaContent));
    }

    /** A final assistant message ({@code assistant.message}). */
    public static AssistantMessageEvent assistantMessage(String content) {
        return assistantMessage(UUID.randomUUID().toString(), content);
    }

    /** A final assistant message with the given message ID. */
    public static AssistantMessageEvent assistantMessage(String messageId, String content) {
        var data = new LinkedHashMap<String, Object>();
        data.put("messageId", messageId);
        data.put("content", content);
        return (AssistantMessageEvent) event("assistant.message", data);
    }

    /** A user prompt echo ({@code user.message}). */
    public static UserMessageEvent userMessage(String content) {
        var data = new LinkedHashMap<String, Object>();
        data.put("messageId", UUID.randomUUID().toString());
        data.put("content", content);
        return (UserMessageEvent) event("user.message", data);
    }

    /** Model token usage ({@code assistant.usage}). */
    public static AssistantUsageEvent usage(String model, long inputTokens, long outputTokens) {
        return (AssistantUsageEvent) event("assistant.usage",
                Map.of("model", model, "inputTokens", inputTokens, "outputTokens", outputTokens));
    }

    /** The session became idle ({@code session.idle}). */
    public static SessionIdleEvent idle(boolean aborted) {
        return (SessionIdleEvent) event("session.idle", Map.of("aborted", aborted));
    }

    /** A session-level error ({@code session.error}). */
    public static SessionErrorEvent error(String errorType, String message) {
        var data = new LinkedHashMap<String, Object>();
        data.put("errorType", errorType);
        data.put("message", message);
        return (SessionErrorEvent) event("session.error", data);
    }

    /** A tool call started ({@code tool.execution_start}). */
    public static ToolExecutionStartEvent toolStart(String toolCallId, String toolName, Map<String, ?> arguments) {
        var data = new LinkedHashMap<String, Object>();
        data.put("toolCallId", toolCallId);
        data.put("toolName", toolName);
        data.put("arguments", arguments == null ? Map.of() : arguments);
        return (ToolExecutionStartEvent) event("tool.execution_start", data);
    }

    /** A tool call completed ({@code tool.execution_complete}); a non-null {@code error} marks it as failed. */
    public static ToolExecutionCompleteEvent toolComplete(String toolCallId, String result, String error) {
        var data = new LinkedHashMap<String, Object>();
        data.put("toolCallId", toolCallId);
        data.put("success", error == null);
        if (error == null) {
            data.put("result", Map.of("content", result == null ? "" : result));
        } else {
            data.put("error", Map.of("message", error));
        }
        return (ToolExecutionCompleteEvent) event("tool.execution_complete", data);
    }
}
