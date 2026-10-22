import { createApprovalStore } from "@copilot-sdk-integrations/ai";
import { createTestCopilot, type CreateTestCopilotOptions, type TestCopilot } from "@copilot-sdk-integrations/core/testing";
import type { UIMessage } from "ai";
import request from "supertest";
import { afterEach, describe, expect, it } from "vitest";
import { Notebook } from "../src/advisor.js";
import { createApp } from "../src/app.js";

let test: TestCopilot | undefined;
afterEach(() => test?.stop({ gracePeriodMs: 0 }));

function setup(script: CreateTestCopilotOptions["script"]) {
  test = createTestCopilot({ script });
  const notebook = new Notebook();
  const approvals = createApprovalStore();
  return { app: createApp({ copilot: test.copilot, notebook, approvals }), runtime: test.runtime, notebook, approvals };
}

/** Parses an AI SDK UI message stream (SSE) into its chunks. */
function chunks(text: string): Array<Record<string, any>> {
  return text
    .split("\n")
    .filter((line) => line.startsWith("data: ") && line !== "data: [DONE]")
    .map((line) => JSON.parse(line.slice(6)));
}

const user = (id: string, text: string): UIMessage => ({ id, role: "user", parts: [{ type: "text", text }] });

describe("express example", () => {
  it("answers with a policy-approved tool call", async () => {
    const { app, runtime } = setup((rt) =>
      rt.onPrompt(/libuv/).callTool("describeTerm", { term: "libuv" }).replyWith((turn) => `From the docs: ${JSON.stringify(turn.toolResult("describeTerm"))}`),
    );
    const res = await request(app).post("/api/ask").set("x-user-id", "ada").send({ question: "What is libuv?" }).expect(200);
    expect(res.body.answer).toContain("libuv is the C library");
    expect(runtime.toolCalls).toMatchObject([{ name: "describeTerm", approved: true }]);
  });

  it("streams a UI message stream over SSE", async () => {
    const { app } = setup((rt) => rt.onPrompt(/.*/).streamReply(["Hello ", "from Copilot"]));
    const res = await request(app).get("/api/stream").query({ q: "hi" }).expect(200);
    expect(res.headers["content-type"]).toContain("text/event-stream");
    const deltas = chunks(res.text).filter((c) => c.type === "text-delta").map((c) => c.delta);
    expect(deltas.join("")).toBe("Hello from Copilot");
  });

  it("asks for approval in useChat and saves the note for the calling user", async () => {
    const { app, notebook, runtime } = setup((rt) =>
      rt.onPrompt(/remember/).callTool("saveNote", { title: "streams", body: "use pipeline()" }).thenReply("Saved."),
    );
    const first = user("u1", "Please remember: use pipeline()");
    const pending = await request(app).post("/api/chat").set("x-user-id", "ada").send({ id: "chat-1", messages: [first] }).expect(200);
    const request1 = chunks(pending.text);
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
    const resumed = await request(app).post("/api/chat").set("x-user-id", "ada").send({ id: "chat-1", messages: [first, answered] }).expect(200);
    const types = chunks(resumed.text).map((c) => c.type);
    expect(types).toContain("tool-output-available");
    expect(chunks(resumed.text).filter((c) => c.type === "text-delta").map((c) => c.delta).join("")).toBe("Saved.");
    expect(notebook.list("ada")).toEqual([{ title: "streams", body: "use pipeline()" }]);
    expect(notebook.list("bob")).toEqual([]);
    expect(runtime.toolCalls).toMatchObject([{ name: "saveNote", approved: true }]);

    const notes = await request(app).get("/api/notes").set("x-user-id", "ada").expect(200);
    expect(notes.body).toHaveLength(1);
  });

  it("maps Copilot errors to HTTP statuses", async () => {
    const { app } = setup((rt) => rt.failStart("no runtime"));
    const res = await request(app).post("/api/ask").send({ question: "What is libuv?" }).expect(503);
    expect(res.body.error).toMatch(/failed to start/);
  });

  it("reports health without starting the runtime", async () => {
    const { app, runtime } = setup(() => {});
    const res = await request(app).get("/health").expect(200);
    expect(res.body.state).toBe("not-started");
    expect(runtime.started).toBe(false);
  });
});
