import type { SessionEvent } from "@github/copilot-sdk";
import { CopilotSessionError } from "./errors.js";
import type { CopilotAgentEvent } from "./types.js";

/** Correlation state for mapping a session's event stream (tool names by tool call id). */
export interface EventMappingState {
  toolNames: Map<string, string>;
}

export function createEventMappingState(): EventMappingState {
  return { toolNames: new Map() };
}

/**
 * Maps an SDK `SessionEvent` to a `CopilotAgentEvent`.
 *
 * | SDK event | Mapped |
 * | --- | --- |
 * | `assistant.message_delta` | `text-delta` (`deltaContent`, `messageId`) |
 * | `assistant.reasoning_delta` | `reasoning-delta` |
 * | `assistant.message` with non-blank content | `message` |
 * | `tool.execution_start` | `tool-start` |
 * | `tool.execution_complete` | `tool-end` (tool name correlated from the start event via `state`) |
 * | `assistant.usage` | `usage` |
 * | `session.error` | `error` (`CopilotSessionError`) |
 * | anything else | `raw` |
 *
 * It never returns `undefined` for a valid event: unmapped events are wrapped as `{ type: "raw" }` so
 * consumers can still reach every SDK event. `turn-start`, `permission-*`, and `done` are produced by
 * the agent executor, not by this mapper (the SDK's `assistant.turn_start` fires once per model call,
 * not once per user turn, so it is passed through as `raw`). The return type keeps `undefined` for
 * non-event input.
 */
export function mapSessionEvent(
  event: SessionEvent,
  state: EventMappingState = createEventMappingState(),
): CopilotAgentEvent | undefined {
  if (!event || typeof event !== "object" || typeof (event as { type?: unknown }).type !== "string") {
    return undefined;
  }
  switch (event.type) {
    case "assistant.message_delta":
      return { type: "text-delta", delta: event.data.deltaContent, messageId: event.data.messageId };
    case "assistant.reasoning_delta":
      return { type: "reasoning-delta", delta: event.data.deltaContent };
    case "assistant.message":
      if (typeof event.data.content !== "string" || event.data.content.trim() === "") {
        return { type: "raw", event };
      }
      return { type: "message", content: event.data.content, messageId: event.data.messageId };
    case "tool.execution_start":
      state.toolNames.set(event.data.toolCallId, event.data.toolName);
      return {
        type: "tool-start",
        toolCallId: event.data.toolCallId,
        toolName: event.data.toolName,
        input: event.data.arguments,
      };
    case "tool.execution_complete": {
      const toolName = state.toolNames.get(event.data.toolCallId) ?? "unknown";
      state.toolNames.delete(event.data.toolCallId);
      const mapped: CopilotAgentEvent = {
        type: "tool-end",
        toolCallId: event.data.toolCallId,
        toolName,
        success: event.data.success,
      };
      if (event.data.result !== undefined) mapped.output = event.data.result.content;
      if (event.data.error !== undefined) mapped.error = event.data.error.message;
      return mapped;
    }
    case "assistant.usage":
      return {
        type: "usage",
        model: event.data.model,
        inputTokens: event.data.inputTokens,
        outputTokens: event.data.outputTokens,
      };
    case "session.error":
      return { type: "error", error: new CopilotSessionError(event.data.message, event.data.errorType) };
    default:
      return { type: "raw", event };
  }
}
