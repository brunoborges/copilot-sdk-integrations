import { createApprovalStore } from "@copilot-sdk-integrations/ai";
import type { CopilotTestRuntime } from "@copilot-sdk-integrations/agent-core/testing";
import { buildCopilotTestApp, parseSse } from "@copilot-sdk-integrations/fastify/testing";
import type { UIMessage } from "ai";
import type { FastifyInstance } from "fastify";
import { afterEach, describe, expect, it } from "vitest";
import { Notebook } from "../src/advisor.js";
import { buildApp } from "../src/app.js";

let current: FastifyInstance | undefined;
afterEach(async () => {
  await current?.close();
  current = undefined;
});

async function setup(script: (runtime: CopilotTestRuntime) => void) {
  const notebook = new Notebook();
  const approvals = createApprovalStore();
  const { app, runtime } = await buildCopilotTestApp((options) => buildApp({ ...options, notebook, approvals }), script);
  current = app;
  return { app, runtime, notebook };
}

/** The AI SDK UI message stream chunks of an SSE body. */
function chunks(body: string): Array<Record<string, any>> {
  return parseSse(body)
    .map((frame) => frame.data)
    .filter((data): data is Record<string, any> => typeof data === "object" && data !== null);
}

const user = (id: string, text: string): UIMessage => ({ id, role: "user", parts: [{ type: "text", text }] });

describe("fastify example", () => {
  it("answers with a policy-approved tool call", async () => {
    const { app, runtime } = await setup((rt) =>
      rt.onPrompt(/libuv/).callTool("describeTerm", { term: "libuv" }).replyWith((turn) => `From the docs: ${JSON.stringify(turn.toolResult("describeTerm"))}`),
    );
    const res = await app.inject({ method: "POST", url: "/api/ask", headers: { "x-user-id": "ada" }, payload: { question: "What is libuv?" } });
    expect(res.statusCode).toBe(200);
    expect(res.json().answer).toContain("libuv is the C library");
    expect(runtime.toolCalls).toMatchObject([{ name: "describeTerm", approved: true }]);
  });

  it("rejects invalid requests with route schema validation", async () => {
    const { app, runtime } = await setup(() => {});
    const ask = await app.inject({ method: "POST", url: "/api/ask", payload: {} });
    expect(ask.statusCode).toBe(400);
    expect(ask.json()).toMatchObject({ code: "FST_ERR_VALIDATION", message: expect.stringContaining("question") });
    const blank = await app.inject({ method: "POST", url: "/api/ask", payload: { question: "   " } });
    expect(blank.statusCode).toBe(400);
    expect((await app.inject("/api/stream")).statusCode).toBe(400);
    expect((await app.inject("/api/events?q=")).statusCode).toBe(400);
    expect(runtime.started).toBe(false);
  });

  it("streams raw Copilot events over SSE", async () => {
    const { app } = await setup((rt) => rt.onPrompt(/.*/).streamReply(["Hello ", "from Copilot"]));
    const res = await app.inject("/api/events?q=hi");
    expect(res.statusCode).toBe(200);
    expect(res.headers["content-type"]).toContain("text/event-stream");
    const frames = parseSse(res.body);
    const deltas = frames.filter((f) => f.event === "text-delta").map((f) => (f.data as { delta: string }).delta);
    expect(deltas.join("")).toBe("Hello from Copilot");
    expect(frames.at(-1)?.event).toBe("done");
  });

  it("streams a UI message stream over SSE", async () => {
    const { app } = await setup((rt) => rt.onPrompt(/.*/).streamReply(["Hello ", "from Copilot"]));
    const res = await app.inject({ method: "GET", url: "/api/stream", query: { q: "hi" } });
    expect(res.statusCode).toBe(200);
    expect(res.headers["content-type"]).toContain("text/event-stream");
    expect(res.headers["x-vercel-ai-ui-message-stream"]).toBe("v1");
    const deltas = chunks(res.body).filter((c) => c.type === "text-delta").map((c) => c.delta);
    expect(deltas.join("")).toBe("Hello from Copilot");
  });

  it("asks for approval in useChat and saves the note for the calling user", async () => {
    const { app, notebook, runtime } = await setup((rt) =>
      rt.onPrompt(/remember/).callTool("saveNote", { title: "streams", body: "use pipeline()" }).thenReply("Saved."),
    );
    const chat = (userId: string, messages: UIMessage[]) =>
      app.inject({ method: "POST", url: "/api/chat", headers: { "x-user-id": userId }, payload: { id: "chat-1", messages } });

    const first = user("u1", "Please remember: use pipeline()");
    const pending = await chat("ada", [first]);
    expect(pending.statusCode).toBe(200);
    const request1 = chunks(pending.body);
    const approval = request1.find((c) => c.type === "tool-approval-request");
    const input = request1.find((c) => c.type === "tool-input-available");
    const start = request1.find((c) => c.type === "start");
    expect(approval).toBeDefined();
    expect(notebook.list("ada")).toEqual([]);

    // What `addToolApprovalResponse({ id, approved: true })` sends back with useChat.
    const answered: UIMessage = {
      id: start!.messageId,
      role: "assistant",
      parts: [
        {
          type: "tool-saveNote",
          toolCallId: input!.toolCallId,
          state: "approval-responded",
          input: input!.input,
          approval: { id: approval!.approvalId, approved: true },
        },
      ],
    };
    const resumed = await chat("ada", [first, answered]);
    expect(resumed.statusCode).toBe(200);
    const resumedChunks = chunks(resumed.body);
    expect(resumedChunks.map((c) => c.type)).toContain("tool-output-available");
    expect(resumedChunks.filter((c) => c.type === "text-delta").map((c) => c.delta).join("")).toBe("Saved.");
    expect(notebook.list("ada")).toEqual([{ title: "streams", body: "use pipeline()" }]);
    expect(notebook.list("bob")).toEqual([]);
    expect(runtime.toolCalls).toMatchObject([{ name: "saveNote", approved: true }]);

    const notes = await app.inject({ url: "/api/notes", headers: { "x-user-id": "ada" } });
    expect(notes.json()).toHaveLength(1);
    expect((await app.inject({ url: "/api/notes", headers: { "x-user-id": "bob" } })).json()).toEqual([]);
  });

  it("maps Copilot errors to HTTP statuses", async () => {
    const { app } = await setup((rt) => rt.failStart("no runtime"));
    const ask = await app.inject({ method: "POST", url: "/api/ask", payload: { question: "What is libuv?" } });
    expect(ask.statusCode).toBe(503);
    expect(ask.json()).toMatchObject({ statusCode: 503, code: expect.stringMatching(/^COPILOT_/), message: expect.stringMatching(/failed to start/) });
    // Streams wait for the first event, so a turn that fails before streaming still gets a real status.
    expect((await app.inject("/api/events?q=hi")).statusCode).toBe(503);
  });

  it("reports health without starting the runtime", async () => {
    const { app, runtime } = await setup(() => {});
    const res = await app.inject("/health");
    expect(res.statusCode).toBe(200);
    expect(res.json().state).toBe("not-started");
    expect(runtime.started).toBe(false);
  });
});
