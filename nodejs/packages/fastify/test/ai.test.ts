import { createApprovalStore } from "@copilot-sdk-integrations/ai";
import { defineAgent, permissions } from "@copilot-sdk-integrations/agent-core";
import { parseSse } from "@copilot-sdk-integrations/agent-core/testing";
import { isToolUIPart, jsonSchema, readUIMessageStream, tool, type UIMessage, type UIMessageChunk } from "ai";
import type { FastifyInstance, FastifyRequest } from "fastify";
import { describe, expect, it } from "vitest";
import { copilotAiPlugin, type CopilotChatBody } from "../src/ai/index.js";
import { setup } from "./helpers.js";

const lookupOrder = tool({
  description: "Look up an order",
  inputSchema: jsonSchema<{ id: string }>({ type: "object", properties: { id: { type: "string" } }, required: ["id"] }),
  execute: async ({ id }) => ({ id, status: "shipped" }),
});
const support = defineAgent({ name: "support", tools: [{ lookupOrder }], permissions: permissions.askUser() });
const chat = defineAgent({ name: "chat" });

const user = (id: string, text: string): UIMessage => ({ id, role: "user", parts: [{ type: "text", text }] });

function chunksOf(body: string): UIMessageChunk[] {
  return parseSse(body)
    .map((frame) => frame.data)
    .filter((data): data is UIMessageChunk => typeof data === "object" && data !== null);
}

async function toMessage(chunks: UIMessageChunk[], message?: UIMessage): Promise<UIMessage> {
  const stream = new ReadableStream<UIMessageChunk>({ start: (c) => (chunks.forEach((x) => c.enqueue(x)), c.close()) });
  let last: UIMessage | undefined;
  for await (const snapshot of readUIMessageStream(message ? { message, stream } : { stream })) last = snapshot;
  return last!;
}

function answer(message: UIMessage, approved: boolean): UIMessage {
  const copy = structuredClone(message);
  for (const part of copy.parts) {
    if (isToolUIPart(part) && part.state === "approval-requested") {
      Object.assign(part, { state: "approval-responded", approval: { id: part.approval.id, approved } });
    }
  }
  return copy;
}

function authenticate(fastify: FastifyInstance) {
  fastify.decorateRequest("user", null);
  fastify.addHook("onRequest", async (request) => {
    const id = request.headers["x-user"];
    if (typeof id === "string") (request as FastifyRequest & { user: unknown }).user = { id };
  });
}

async function chatApp(script: Parameters<typeof setup>[1]["script"], options: { approvals?: ReturnType<typeof createApprovalStore> } = {}) {
  const result = await setup({ agents: [support, chat] }, { script }, authenticate);
  await result.fastify.register(copilotAiPlugin, options);
  result.fastify.post<{ Body: CopilotChatBody }>("/chat/:agent", async (request, reply) =>
    reply.copilotChat({ agent: (request.params as { agent: string }).agent }),
  );
  return result;
}

describe("copilotAiPlugin", () => {
  it("requires copilotPlugin", async () => {
    const { default: Fastify } = await import("fastify");
    const fastify = Fastify();
    await expect(fastify.register(copilotAiPlugin).ready()).rejects.toThrow(/@copilot-sdk-integrations\/fastify/);
  });

  it("streams a useChat UI message stream with principal-scoped conversations", async () => {
    const { fastify, test } = await chatApp((rt) => rt.onPrompt(/.*/).reply("Hi there"));
    const send = (userId: string, messages: UIMessage[]) =>
      fastify.inject({ method: "POST", url: "/chat/chat", headers: { "x-user": userId }, payload: { id: "chat-1", messages } });

    const res = await send("alice", [user("u1", "Hello")]);
    expect(res.statusCode).toBe(200);
    expect(res.headers["content-type"]).toContain("text/event-stream");
    expect(res.headers["x-vercel-ai-ui-message-stream"]).toBe("v1");
    const chunks = chunksOf(res.body);
    expect(chunks.map((c) => c.type)).toEqual(["start", "text-start", "text-delta", "text-end", "finish"]);
    expect(res.body).toContain("data: [DONE]");

    await send("alice", [user("u1", "Hello"), user("u2", "Again")]);
    await send("bob", [user("u1", "Hello")]);
    expect(test.runtime.prompts).toEqual(["Hello", "Again", "Hello"]);
    expect(new Set(test.runtime.sessionIds).size).toBe(2);
  });

  it("round-trips a tool approval across requests", async () => {
    const { fastify, test } = await chatApp((rt) => rt.onPrompt(/order/).callTool("lookupOrder", { id: "7" }).thenReply("Order 7 shipped."));
    const first = user("u1", "Where is order 7?");
    const post = (messages: UIMessage[]) =>
      fastify.inject({ method: "POST", url: "/chat/support", headers: { "x-user": "alice" }, payload: { id: "c1", messages } });

    const firstChunks = chunksOf((await post([first])).body);
    expect(firstChunks.map((c) => c.type)).toEqual(["start", "tool-input-available", "tool-approval-request", "finish"]);
    expect(fastify.copilotApprovals.suspendedTurns).toBe(1);

    const pending = await toMessage(firstChunks);
    const responded = answer(pending, true);
    const final = await toMessage(chunksOf((await post([first, responded])).body), responded);
    expect(final.parts.filter((p) => p.type !== "step-start")).toMatchObject([
      { type: "tool-lookupOrder", state: "output-available" },
      { type: "text", text: "Order 7 shipped." },
    ]);
    expect(test.runtime.toolCalls).toMatchObject([{ name: "lookupOrder", approved: true }]);
    expect(fastify.copilotApprovals.suspendedTurns).toBe(0);
  });

  it("only lets the owner answer an approval", async () => {
    const { fastify, test } = await setup(
      { agents: [support], context: { principal: (request) => request.headers["x-user"] as string | undefined } },
      { script: (rt) => rt.onPrompt(/order/).callTool("lookupOrder", { id: "7" }).thenReply("Order 7 shipped.") },
    );
    await fastify.register(copilotAiPlugin);
    // The default `${principal}:${id}` scoping, and an unscoped conversation id that relies on the approval owner check alone.
    fastify.post<{ Body: CopilotChatBody }>("/chat", async (request, reply) => reply.copilotChat({ agent: "support" }));
    fastify.post<{ Body: CopilotChatBody }>("/raw", async (request, reply) =>
      reply.copilotChat({ agent: "support", conversationId: request.body.id ?? "none" }),
    );
    const post = (url: string, userId: string, messages: UIMessage[]) =>
      fastify.inject({ method: "POST", url, headers: { "x-user": userId }, payload: { id: "c1", messages } });
    const first = user("u1", "Where is order 7?");

    for (const url of ["/chat", "/raw"]) {
      const responded = answer(await toMessage(chunksOf((await post(url, "alice", [first])).body)), true);
      expect(fastify.copilotApprovals.suspendedTurns).toBe(1);

      const stolen = chunksOf((await post(url, "mallory", [first, responded])).body);
      expect(stolen.map((c) => c.type)).toEqual(["start", "tool-output-error", "finish"]);
      expect(stolen[1]).toMatchObject({ errorText: expect.stringMatching(/expired/) });
      expect(fastify.copilotApprovals.suspendedTurns).toBe(1);
      expect(test.runtime.toolCalls.filter((call) => call.approved)).toHaveLength(url === "/chat" ? 0 : 1);

      const owned = chunksOf((await post(url, "alice", [first, responded])).body);
      expect(owned.map((c) => c.type)).toContain("tool-output-available");
      expect(fastify.copilotApprovals.suspendedTurns).toBe(0);
    }
    expect(test.runtime.toolCalls.filter((call) => call.approved)).toHaveLength(2);
  });

  it("uses a provided approval store", async () => {
    const approvals = createApprovalStore();
    const { fastify } = await chatApp(undefined, { approvals });
    expect(fastify.copilotApprovals).toBe(approvals);
  });

  it("reply.copilotUIStream streams any event iterable", async () => {
    const { fastify } = await setup({ agents: [chat] }, { script: (rt) => rt.onPrompt(/.*/).reply("Hello") });
    await fastify.register(copilotAiPlugin);
    fastify.get("/ui", async (request, reply) => reply.copilotUIStream(request.copilot.agent("chat").stream({ prompt: "hi" }), { status: 201 }));
    const res = await fastify.inject("/ui");
    expect(res.statusCode).toBe(201);
    expect(chunksOf(res.body).filter((c) => c.type === "text-delta")).toMatchObject([{ delta: "Hello" }]);
  });
});
