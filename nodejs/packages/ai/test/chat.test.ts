import { describe, expect, it } from "vitest";
import {
  isToolUIPart,
  jsonSchema,
  lastAssistantMessageIsCompleteWithApprovalResponses,
  readUIMessageStream,
  tool,
  type UIMessage,
  type UIMessageChunk,
} from "ai";
import { defineAgent, permissions } from "@copilot-sdk-integrations/core";
import { createApprovalStore, createCopilotChatResponse, fromUIMessages, streamCopilotChat } from "../src/index.js";
import { collect, setup, types } from "./helpers.js";

const lookupOrder = tool({
  description: "Look up an order",
  inputSchema: jsonSchema<{ id: string }>({ type: "object", properties: { id: { type: "string" } }, required: ["id"] }),
  execute: async ({ id }) => ({ id, status: "shipped" }),
});
const support = defineAgent({ name: "support", tools: [{ lookupOrder }], permissions: permissions.askUser() });

const user = (id: string, text: string): UIMessage => ({ id, role: "user", parts: [{ type: "text", text }] });

async function toMessage(stream: ReadableStream<UIMessageChunk>, message?: UIMessage): Promise<UIMessage> {
  let last: UIMessage | undefined;
  for await (const snapshot of readUIMessageStream(message ? { message, stream } : { stream })) last = snapshot;
  return last!;
}

/** What `addToolApprovalResponse` does on the client. */
function answer(message: UIMessage, approved: boolean, reason?: string): UIMessage {
  const copy = structuredClone(message);
  for (const part of copy.parts) {
    if (isToolUIPart(part) && part.state === "approval-requested") {
      Object.assign(part, { state: "approval-responded", approval: { id: part.approval.id, approved, ...(reason ? { reason } : {}) } });
    }
  }
  return copy;
}

describe("fromUIMessages", () => {
  it("reads the latest user message", () => {
    const messages: UIMessage[] = [
      user("u1", "old"),
      { id: "a1", role: "assistant", parts: [{ type: "text", text: "reply" }] },
      { id: "u2", role: "user", parts: [{ type: "text", text: "line 1" }, { type: "file", mediaType: "image/png", url: "x" }, { type: "text", text: "line 2" }] },
    ];
    expect(fromUIMessages(messages, { conversationId: "c1" })).toEqual({ conversationId: "c1", prompt: "line 1\nline 2", approvalResponses: [] });
  });

  it("reads approval responses from the last assistant message", () => {
    const messages: UIMessage[] = [
      user("u1", "pay"),
      {
        id: "a1",
        role: "assistant",
        parts: [
          { type: "dynamic-tool", toolName: "pay", toolCallId: "t1", state: "approval-responded", input: {}, approval: { id: "p1", approved: false, reason: "too much" } },
          { type: "tool-x", toolCallId: "t2", state: "approval-requested", input: {}, approval: { id: "p2" } },
        ],
      },
    ];
    expect(fromUIMessages(messages)).toEqual({
      messageId: "a1",
      approvalResponses: [{ approvalId: "p1", toolCallId: "t1", approved: false, reason: "too much" }],
    });
  });

  it("handles empty input", () => {
    expect(fromUIMessages([])).toEqual({ approvalResponses: [] });
  });
});

describe("streamCopilotChat", () => {
  it("streams a plain chat turn and keeps history by conversation", async () => {
    const { copilot, runtime } = setup({ script: (rt) => rt.onPrompt(/.*/).reply("Hi there") });
    const agent = copilot.agent(defineAgent({ name: "chat" }));
    const chunks = await collect(streamCopilotChat({ agent, messages: [user("u1", "Hello")], conversationId: "chat-1" }));
    expect(types(chunks)).toEqual(["start", "text-start", "text-delta", "text-end", "finish"]);
    await collect(streamCopilotChat({ agent, messages: [user("u1", "Hello"), user("u2", "Again")], conversationId: "chat-1" }));
    expect(runtime.prompts).toEqual(["Hello", "Again"]);
    expect(runtime.sessionIds).toHaveLength(1);
  });

  it("suspends on an approval request and resumes the same turn when approved", async () => {
    const { copilot, runtime } = setup({ script: (rt) => rt.onPrompt(/order/).callTool("lookupOrder", { id: "7" }).thenReply("Order 7 shipped.") });
    const agent = copilot.agent(support);
    const approvals = createApprovalStore();
    const first = user("u1", "Where is order 7?");

    const firstChunks = await collect(streamCopilotChat({ agent, messages: [first], conversationId: "c1", approvals }));
    expect(types(firstChunks)).toEqual(["start", "tool-input-available", "tool-approval-request", "finish"]);
    expect(firstChunks.at(-1)).toMatchObject({ finishReason: "tool-calls" });
    expect(approvals.suspendedTurns).toBe(1);
    expect(runtime.toolCalls.filter((call) => call.approved)).toHaveLength(0);

    const pending = await toMessage(new ReadableStream({ start: (c) => (firstChunks.forEach((x) => c.enqueue(x)), c.close()) }));
    expect(pending.parts.find(isToolUIPart)).toMatchObject({ type: "tool-lookupOrder", state: "approval-requested", input: { id: "7" } });

    const responded = answer(pending, true);
    const messages = [first, responded];
    expect(lastAssistantMessageIsCompleteWithApprovalResponses({ messages })).toBe(true);

    const resumed = streamCopilotChat({ agent, messages, conversationId: "c1", approvals });
    const final = await toMessage(resumed, responded);
    expect(final.id).toBe(pending.id);
    expect(final.parts.filter((p) => p.type !== "step-start")).toMatchObject([
      { type: "tool-lookupOrder", state: "output-available", approval: { approved: true } },
      { type: "text", text: "Order 7 shipped.", state: "done" },
    ]);
    expect(runtime.toolCalls).toMatchObject([{ name: "lookupOrder", approved: true }]);
    expect(approvals.suspendedTurns).toBe(0);
    expect(runtime.prompts).toEqual(["Where is order 7?"]);
  });

  it("resumes with a denial", async () => {
    const { copilot, runtime } = setup({ script: (rt) => rt.onPrompt(/order/).callTool("lookupOrder", { id: "7" }).thenReply("Not allowed.") });
    const agent = copilot.agent(support);
    const approvals = createApprovalStore();
    const first = user("u1", "Where is order 7?");
    const pending = await toMessage(streamCopilotChat({ agent, messages: [first], approvals }));
    const responded = answer(pending, false, "no thanks");
    const chunks = await collect(streamCopilotChat({ agent, messages: [first, responded], approvals }));
    expect(types(chunks)).toEqual(["start", "tool-approval-response", "tool-output-denied", "text-start", "text-delta", "text-end", "finish"]);
    expect(runtime.toolCalls).toMatchObject([{ name: "lookupOrder", approved: false }]);
    const final = await toMessage(new ReadableStream({ start: (c) => (chunks.forEach((x) => c.enqueue(x)), c.close()) }), responded);
    expect(final.parts.find(isToolUIPart)).toMatchObject({ state: "output-denied" });
  });

  it("reports unknown approvals as tool errors", async () => {
    const { copilot } = setup();
    const agent = copilot.agent(support);
    const messages: UIMessage[] = [
      user("u1", "x"),
      {
        id: "a1",
        role: "assistant",
        parts: [{ type: "tool-lookupOrder", toolCallId: "t1", state: "approval-responded", input: { id: "1" }, approval: { id: "gone", approved: true } }],
      },
    ];
    const chunks = await collect(streamCopilotChat({ agent, messages, approvals: createApprovalStore() }));
    expect(chunks).toEqual([
      { type: "start", messageId: "a1" },
      { type: "tool-output-error", toolCallId: "t1", errorText: expect.stringMatching(/expired/) },
      { type: "finish", finishReason: "stop" },
    ]);
  });

  it("a new user message supersedes a suspended turn", async () => {
    const { copilot, runtime } = setup({
      script: (rt) => rt.onPrompt(/order/).callTool("lookupOrder", { id: "7" }).thenReply("late").onPrompt(/never mind/).reply("OK."),
    });
    const agent = copilot.agent(support);
    const approvals = createApprovalStore();
    const first = user("u1", "Where is order 7?");
    const pending = await toMessage(streamCopilotChat({ agent, messages: [first], conversationId: "c2", approvals }));
    expect(approvals.suspendedTurns).toBe(1);
    const chunks = await collect(
      streamCopilotChat({ agent, messages: [first, pending, user("u2", "never mind")], conversationId: "c2", approvals }),
    );
    expect(chunks.filter((c) => c.type === "text-delta")).toMatchObject([{ delta: "OK." }]);
    expect(approvals.suspendedTurns).toBe(0);
    expect(runtime.toolCalls.filter((c) => c.approved)).toHaveLength(0);
  });

  it("aborts suspended turns after the TTL", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).callTool("lookupOrder", { id: "7" }).thenReply("late") });
    const agent = copilot.agent(support);
    const approvals = createApprovalStore({ ttlMs: 20 });
    await collect(streamCopilotChat({ agent, messages: [user("u1", "x")], approvals }));
    expect(approvals.suspendedTurns).toBe(1);
    await expect.poll(() => approvals.suspendedTurns).toBe(0);
    await expect.poll(() => copilot.capacity().activeTurns).toBe(0);
  });

  it("returns an error stream without a user message", async () => {
    const { copilot } = setup();
    const chunks = await collect(streamCopilotChat({ agent: copilot.agent(support), messages: [] }));
    expect(types(chunks)).toEqual(["start", "error", "finish"]);
  });

  it("createCopilotChatResponse returns an SSE response", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).reply("Hello") });
    const response = createCopilotChatResponse({ agent: copilot.agent(defineAgent({ name: "chat" })), messages: [user("u1", "Hi")], status: 201 });
    expect(response.status).toBe(201);
    expect(response.headers.get("content-type")).toContain("text/event-stream");
    expect(await response.text()).toContain('"delta":"Hello"');
  });
});
