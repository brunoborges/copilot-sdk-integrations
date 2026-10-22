import { describe, expect, it } from "vitest";
import { CopilotSessionError } from "../src/errors.js";
import { createEventMappingState, mapSessionEvent } from "../src/events.js";
import { sdkEvent } from "./fakes.js";

describe("mapSessionEvent", () => {
  it("maps deltas", () => {
    expect(mapSessionEvent(sdkEvent("assistant.message_delta", { messageId: "m", deltaContent: "Hi" }))).toEqual({
      type: "text-delta",
      delta: "Hi",
      messageId: "m",
    });
    expect(mapSessionEvent(sdkEvent("assistant.reasoning_delta", { reasoningId: "r", deltaContent: "hmm" }))).toEqual({
      type: "reasoning-delta",
      delta: "hmm",
    });
  });

  it("maps assistant messages and passes blank ones (tool-call only) through as raw", () => {
    expect(mapSessionEvent(sdkEvent("assistant.message", { messageId: "m", content: "Done" }))).toEqual({
      type: "message",
      content: "Done",
      messageId: "m",
    });
    const blank = sdkEvent("assistant.message", { messageId: "m", content: "  ", toolRequests: [] });
    expect(mapSessionEvent(blank)).toEqual({ type: "raw", event: blank });
  });

  it("correlates tool names between start and complete", () => {
    const state = createEventMappingState();
    expect(
      mapSessionEvent(sdkEvent("tool.execution_start", { toolCallId: "t1", toolName: "lookup", arguments: { id: 1 } }), state),
    ).toEqual({ type: "tool-start", toolCallId: "t1", toolName: "lookup", input: { id: 1 } });
    expect(
      mapSessionEvent(
        sdkEvent("tool.execution_complete", { toolCallId: "t1", success: true, result: { content: "found" } }),
        state,
      ),
    ).toEqual({ type: "tool-end", toolCallId: "t1", toolName: "lookup", success: true, output: "found" });
    expect(state.toolNames.size).toBe(0);
  });

  it("maps failed tools and unknown tool call ids", () => {
    expect(
      mapSessionEvent(sdkEvent("tool.execution_complete", { toolCallId: "x", success: false, error: { message: "boom" } })),
    ).toEqual({ type: "tool-end", toolCallId: "x", toolName: "unknown", success: false, error: "boom" });
  });

  it("maps usage", () => {
    expect(
      mapSessionEvent(sdkEvent("assistant.usage", { model: "gpt", inputTokens: 1, outputTokens: 2, cost: 1 })),
    ).toEqual({ type: "usage", model: "gpt", inputTokens: 1, outputTokens: 2 });
  });

  it("maps session errors to CopilotSessionError", () => {
    const mapped = mapSessionEvent(sdkEvent("session.error", { errorType: "quota", message: "Out of quota" }));
    expect(mapped?.type).toBe("error");
    const error = (mapped as { error: CopilotSessionError }).error;
    expect(error).toBeInstanceOf(CopilotSessionError);
    expect(error.message).toContain("Out of quota");
    expect(error.errorType).toBe("quota");
  });

  it("wraps everything else as raw", () => {
    for (const type of ["session.idle", "user.message", "assistant.turn_start", "session.start", "whatever.new"]) {
      const event = sdkEvent(type, {});
      expect(mapSessionEvent(event)).toEqual({ type: "raw", event });
    }
  });

  it("returns undefined for non-events", () => {
    expect(mapSessionEvent(undefined as never)).toBeUndefined();
    expect(mapSessionEvent({} as never)).toBeUndefined();
  });
});
