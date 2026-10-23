import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { describe, expect, it } from "vitest";
import { jsonSchema, readUIMessageStream, tool, type UIMessage, type UIMessageChunk } from "ai";
import { defineAgent, permissions } from "@copilot-sdk-integrations/agent-core";
import {
  pipeCopilotEventsToResponse,
  toUIMessageStream,
  toUIMessageStreamResponse,
  type CopilotMessageMetadata,
} from "../src/index.js";
import { collect, setup, types } from "./helpers.js";

const assistant = defineAgent({ name: "assistant" });
const lookupOrder = tool({
  description: "Look up an order",
  inputSchema: jsonSchema<{ id: string }>({ type: "object", properties: { id: { type: "string" } }, required: ["id"] }),
  execute: async ({ id }) => ({ id, status: "shipped" }),
});

function textOf(chunks: UIMessageChunk[]): string {
  return chunks.map((chunk) => (chunk.type === "text-delta" ? chunk.delta : "")).join("");
}

describe("toUIMessageStream", () => {
  it("maps a reply to start/text/finish chunks with metadata", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).reply("Hello").usage({ inputTokens: 10, outputTokens: 4, model: "gpt-test" }) });
    const chunks = await collect(toUIMessageStream(copilot.agent(assistant).stream({ prompt: "Hi" }), { messageId: "m1" }));
    expect(types(chunks)).toEqual(["start", "text-start", "text-delta", "text-end", "finish"]);
    expect(chunks[0]).toEqual({ type: "start", messageId: "m1" });
    expect(textOf(chunks)).toBe("Hello");
    const finish = chunks.at(-1) as Extract<UIMessageChunk, { type: "finish" }>;
    expect(finish.finishReason).toBe("stop");
    const metadata = finish.messageMetadata as CopilotMessageMetadata;
    expect(metadata.sessionId).toEqual(expect.any(String));
    expect(metadata.model).toBe("gpt-test");
    expect(metadata.usage).toEqual({ inputTokens: 10, outputTokens: 4, totalTokens: 14 });
  });

  it("streams deltas into one text part", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).streamReply(["Hel", "lo", " world"]) });
    const chunks = await collect(toUIMessageStream(copilot.agent(assistant).stream({ prompt: "Hi" })));
    const textTypes = types(chunks).filter((t) => t.startsWith("text-"));
    expect(textTypes[0]).toBe("text-start");
    expect(textTypes.at(-1)).toBe("text-end");
    expect(textTypes.filter((t) => t === "text-start")).toHaveLength(1);
    expect(textOf(chunks)).toBe("Hello world");
  });

  it("maps reasoning before text, and can drop it", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).reasoning("Thinking...").reply("Answer") });
    const chunks = await collect(toUIMessageStream(copilot.agent(assistant).stream({ prompt: "Hi" })));
    expect(types(chunks)).toEqual([
      "start",
      "reasoning-start",
      "reasoning-delta",
      "reasoning-end",
      "text-start",
      "text-delta",
      "text-end",
      "finish",
    ]);
    const reasoning = chunks
      .filter((c): c is Extract<UIMessageChunk, { type: "reasoning-delta" }> => c.type === "reasoning-delta")
      .map((c) => c.delta)
      .join("");
    expect(reasoning).toBe("Thinking...");

    const hidden = await collect(toUIMessageStream(copilot.agent(assistant).stream({ prompt: "Hi" }), { sendReasoning: false }));
    expect(types(hidden).some((t) => t.startsWith("reasoning"))).toBe(false);
  });

  it("maps tool calls to tool input/output chunks", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).callTool("lookupOrder", { id: "42" }).thenReply("Order 42 shipped.") });
    const agent = copilot.agent(defineAgent({ name: "support", tools: [{ lookupOrder }], permissions: permissions.allowAll() }));
    const chunks = await collect(toUIMessageStream(agent.stream({ prompt: "Where is order 42?" })));
    expect(types(chunks)).toEqual(["start", "tool-input-available", "tool-output-available", "text-start", "text-delta", "text-end", "finish"]);
    expect(chunks[1]).toMatchObject({ type: "tool-input-available", toolName: "lookupOrder", input: { id: "42" } });
    expect(chunks[2]).toMatchObject({ type: "tool-output-available", toolCallId: (chunks[1] as { toolCallId: string }).toolCallId });
    expect(textOf(chunks)).toBe("Order 42 shipped.");
  });

  it("marks tool parts dynamic on request", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).callTool("lookupOrder", { id: "1" }).thenReply("ok") });
    const agent = copilot.agent(defineAgent({ name: "support", tools: [{ lookupOrder }], permissions: permissions.allowAll() }));
    const chunks = await collect(toUIMessageStream(agent.stream({ prompt: "x" }), { dynamicTools: true }));
    expect(chunks.find((c) => c.type === "tool-input-available")).toMatchObject({ dynamic: true });
    expect(chunks.find((c) => c.type === "tool-output-available")).toMatchObject({ dynamic: true });
  });

  it("maps denied tools to tool-output-error", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).callTool("lookupOrder", { id: "1" }).thenReply("denied") });
    const agent = copilot.agent(defineAgent({ name: "support", tools: [{ lookupOrder }] }));
    const chunks = await collect(toUIMessageStream(agent.stream({ prompt: "x" })));
    expect(types(chunks)).toContain("tool-output-error");
  });

  it("maps failures to an error chunk and an error finish", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).fail("boom") });
    const chunks = await collect(toUIMessageStream(copilot.agent(assistant).stream({ prompt: "x" }), { onError: () => "custom error" }));
    expect(types(chunks)).toEqual(["start", "error", "finish"]);
    expect(chunks[1]).toEqual({ type: "error", errorText: "custom error" });
    expect(chunks[2]).toMatchObject({ type: "finish", finishReason: "error" });
  });

  it("supports custom metadata and omitting start/finish", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).reply("ok") });
    const chunks = await collect(
      toUIMessageStream(copilot.agent(assistant).stream({ prompt: "x" }), {
        messageMetadata: ({ part }) => ({ part }),
      }),
    );
    expect(chunks[0]).toMatchObject({ type: "start", messageMetadata: { part: "start" } });
    expect(chunks.at(-1)).toMatchObject({ type: "finish", messageMetadata: { part: "finish" } });

    const bare = await collect(toUIMessageStream(copilot.agent(assistant).stream({ prompt: "x" }), { sendStart: false, sendFinish: false }));
    expect(types(bare)).toEqual(["text-start", "text-delta", "text-end"]);
  });

  it("answers approvals inline through onApprovalRequest", async () => {
    const { copilot, runtime } = setup({ script: (rt) => rt.onPrompt(/.*/).callTool("lookupOrder", { id: "7" }).thenReply("done") });
    const agent = copilot.agent(defineAgent({ name: "support", tools: [{ lookupOrder }], permissions: permissions.askUser() }));
    const seen: string[] = [];
    const chunks = await collect(
      toUIMessageStream(agent.stream({ prompt: "x" }), {
        onApprovalRequest: (approval) => {
          seen.push(approval.toolName);
          approval.respond({ approved: true });
        },
      }),
    );
    expect(seen).toEqual(["lookupOrder"]);
    expect(types(chunks)).toEqual([
      "start",
      "tool-input-available",
      "tool-approval-request",
      "tool-approval-response",
      "tool-output-available",
      "text-start",
      "text-delta",
      "text-end",
      "finish",
    ]);
    expect(chunks[3]).toMatchObject({ type: "tool-approval-response", approved: true });
    expect(runtime.toolCalls).toMatchObject([{ name: "lookupOrder", approved: true }]);
  });

  it("maps an inline denial to tool-output-denied", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).callTool("lookupOrder", { id: "7" }).thenReply("done") });
    const agent = copilot.agent(defineAgent({ name: "support", tools: [{ lookupOrder }], permissions: permissions.askUser() }));
    const chunks = await collect(
      toUIMessageStream(agent.stream({ prompt: "x" }), { onApprovalRequest: (approval) => approval.respond({ approved: false }) }),
    );
    expect(types(chunks)).toContain("tool-output-denied");
    expect(types(chunks)).not.toContain("tool-output-available");
    expect(chunks.find((c) => c.type === "tool-approval-response")).toMatchObject({ approved: false });
  });

  it("round-trips through readUIMessageStream", async () => {
    const { copilot } = setup({
      script: (rt) => rt.onPrompt(/.*/).reasoning("Hmm").callTool("lookupOrder", { id: "42" }).thenReply("Shipped."),
    });
    const agent = copilot.agent(defineAgent({ name: "support", tools: [{ lookupOrder }], permissions: permissions.allowAll() }));
    let message: UIMessage | undefined;
    for await (const snapshot of readUIMessageStream({ stream: toUIMessageStream(agent.stream({ prompt: "x" }), { messageId: "a1" }) })) {
      message = snapshot;
    }
    expect(message?.id).toBe("a1");
    expect(message?.parts.filter((p) => p.type !== "step-start")).toMatchObject([
      { type: "reasoning", text: "Hmm", state: "done" },
      { type: "tool-lookupOrder", state: "output-available", input: { id: "42" }, output: JSON.stringify({ id: "42", status: "shipped" }) },
      { type: "text", text: "Shipped.", state: "done" },
    ]);
    expect(message?.metadata).toMatchObject({ sessionId: expect.any(String) });
  });

  it("aborts the turn when the stream is cancelled", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).streamReply(["a"]).delay(5_000).reply("late") });
    const stream = toUIMessageStream(copilot.agent(assistant).stream({ prompt: "x" }));
    const reader = stream.getReader();
    await reader.read();
    await reader.cancel();
    await expect.poll(() => copilot.capacity().activeTurns).toBe(0);
  });
});

describe("responses", () => {
  it("toUIMessageStreamResponse returns an SSE response", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).reply("Hello") });
    const response = toUIMessageStreamResponse(copilot.agent(assistant).stream({ prompt: "x" }), { headers: { "x-test": "1" } });
    expect(response.headers.get("content-type")).toContain("text/event-stream");
    expect(response.headers.get("x-vercel-ai-ui-message-stream")).toBe("v1");
    expect(response.headers.get("x-test")).toBe("1");
    const body = await response.text();
    expect(body).toContain('"type":"text-delta"');
    expect(body).toContain("data: [DONE]");
  });

  it("pipeCopilotEventsToResponse writes SSE to a Node response", async () => {
    const { copilot } = setup({ script: (rt) => rt.onPrompt(/.*/).reply("Hello node") });
    const server = createServer((_req, res) => {
      void pipeCopilotEventsToResponse(res, copilot.agent(assistant).stream({ prompt: "x" }));
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    try {
      const { port } = server.address() as AddressInfo;
      const response = await fetch(`http://127.0.0.1:${port}/`);
      expect(response.headers.get("content-type")).toContain("text/event-stream");
      const body = await response.text();
      expect(body).toContain("Hello node");
      expect(body).toContain('"type":"finish"');
    } finally {
      await new Promise((resolve) => server.close(resolve));
    }
  });
});
