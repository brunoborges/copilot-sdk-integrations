package io.github.brunoborges.copilot.camel;

/** Exchange header names used by the {@code copilot} component. */
public final class CopilotConstants {

    /** Conversation to continue on {@code copilot:agent}; set on the reply for named conversations. */
    public static final String CONVERSATION_ID = "CopilotConversationId";
    /** Name of the agent that produced the reply or the event. */
    public static final String AGENT_NAME = "CopilotAgentName";
    /** Copilot session of the tool call, permission request or event. */
    public static final String SESSION_ID = "CopilotSessionId";
    /** Name of the tool being invoked on a {@code copilot:tool} route. */
    public static final String TOOL_NAME = "CopilotToolName";
    /** Tool call identifier on {@code copilot:tool} and {@code copilot:permission} routes. */
    public static final String TOOL_CALL_ID = "CopilotToolCallId";
    /** Permission kind (for example {@code shell}, {@code write}, {@code custom-tool}) on {@code copilot:permission}. */
    public static final String PERMISSION_KIND = "CopilotPermissionKind";
    /** Session event type (for example {@code assistant.message}) on {@code copilot:events}. */
    public static final String EVENT_TYPE = "CopilotEventType";

    private CopilotConstants() {
    }
}
