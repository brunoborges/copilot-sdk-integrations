import "reflect-metadata";
import { CopilotTestingModule, CopilotTestRuntime } from "@copilot-sdk-integrations/nestjs/testing";
import type { INestApplication } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import type { UIMessage } from "ai";
import request from "supertest";
import { afterEach, describe, expect, it } from "vitest";
import { NotesService } from "../src/advisor/notes.service";
import { AppModule } from "../src/app.module";
import { AppCopilotModule } from "../src/copilot.config";

let app: INestApplication | undefined;

afterEach(async () => {
  await app?.close();
  app = undefined;
});

/** Boots the real `AppModule` with the scripted Copilot runtime instead of the CLI (no credentials). */
async function boot(script: (runtime: CopilotTestRuntime) => unknown = () => {}) {
  const moduleRef = await Test.createTestingModule({ imports: [AppModule] })
    .overrideModule(AppCopilotModule)
    .useModule(CopilotTestingModule.forRoot(script))
    .compile();
  app = moduleRef.createNestApplication();
  await app.init();
  return { http: request(app.getHttpServer()), runtime: app.get(CopilotTestRuntime), notes: app.get(NotesService) };
}

/** Parses an SSE body into `{ event, data }` records. */
function sse(text: string): Array<{ event?: string; data: any }> {
  return text
    .split("\n\n")
    .filter((block) => block.includes("data: "))
    .map((block) => {
      const lines = block.split("\n");
      const event = lines.find((l) => l.startsWith("event: "))?.slice(7);
      const data = lines.find((l) => l.startsWith("data: "))!.slice(6);
      return { ...(event ? { event } : {}), data: data === "[DONE]" ? data : JSON.parse(data) };
    });
}

const user = (id: string, text: string): UIMessage => ({ id, role: "user", parts: [{ type: "text", text }] });

describe("NestJS chat example", () => {
  it("reports health without starting the runtime or requiring a user", async () => {
    const { http, runtime } = await boot();
    const res = await http.get("/health").expect(200);
    expect(res.body.info.copilot).toMatchObject({ status: "up", state: "not-started" });
    expect(runtime.started).toBe(false);
  });

  it("requires an x-user-id header", async () => {
    const { http } = await boot();
    await http.post("/api/conversations").send({ question: "hi" }).expect(401);
  });

  it("answers with a policy-approved provider tool and continues the conversation", async () => {
    const { http, runtime } = await boot((rt) =>
      rt
        .onPrompt(/libuv/)
        .callTool("describeTerm", { term: "libuv" })
        .replyWith((turn) => `Per the reference: ${String(turn.toolResult("describeTerm"))}`)
        .onPrompt(/thanks/)
        .reply("You're welcome."),
    );
    const started = await http.post("/api/conversations").set("x-user-id", "ada").send({ question: "What is libuv?" }).expect(201);
    expect(started.body.answer).toContain("libuv is the C library");
    expect(runtime.toolCalls).toMatchObject([{ name: "describeTerm", approved: true }]);

    const next = await http.post(`/api/conversations/${started.body.conversationId}/messages`).set("x-user-id", "ada").send({ question: "thanks" }).expect(200);
    expect(next.body.answer).toBe("You're welcome.");
    expect(runtime.sessionIds).toHaveLength(1);

    // Conversations are scoped to the principal: another user can't resume ada's conversation.
    await http.post(`/api/conversations/${started.body.conversationId}/messages`).set("x-user-id", "grace").send({ question: "thanks" }).expect(404);
  });

  it("maps Copilot failures to HTTP errors and validates input", async () => {
    const { http } = await boot((rt) => rt.onPrompt(/boom/).fail("model exploded"));
    const res = await http.post("/api/conversations").set("x-user-id", "ada").send({ question: "boom" });
    expect(res.status).toBe(502); // CopilotExceptionFilter: session error -> Bad Gateway, without internals
    expect(JSON.stringify(res.body)).not.toContain("at mapSessionEvent");
    await http.post("/api/conversations").set("x-user-id", "ada").send({ question: "" }).expect(400);
  });

  it("returns structured output with @Returns", async () => {
    const explanation = { term: "streams", summary: "Chunked data with backpressure.", level: "beginner", related: ["pipeline"] };
    const { http, runtime } = await boot((rt) => rt.onPrompt(/"streams"/).replyJson(explanation).onPrompt(/"broken"/).reply("not json"));
    const res = await http.get("/api/explain").query({ term: "streams" }).set("x-user-id", "ada").expect(200);
    expect(res.body).toEqual(explanation);
    expect(runtime.prompts[0]).toContain('Explain the Node.js term "streams" for a beginner developer');
    await http.get("/api/explain").query({ term: "broken" }).set("x-user-id", "ada").expect(502);
  });

  it("streams agent events over SSE and resolves an ask-user permission", async () => {
    const { runtime, notes } = await boot((rt) =>
      rt.onPrompt(/remember/).callTool("saveNote", { title: "streams", body: "Use pipeline()." }).thenReply("Saved it."),
    );
    await app!.listen(0);
    const base = await app!.getUrl();
    const headers = { "x-user-id": "ada" };

    const response = await fetch(`${base}/api/conversations/c1/stream?q=${encodeURIComponent("remember: use pipeline()")}`, { headers });
    expect(response.headers.get("content-type")).toContain("text/event-stream");
    const reader = response.body!.pipeThrough(new TextDecoderStream()).getReader();
    // Read until the complete permission-request event has arrived; the turn waits for the answer.
    let text = "";
    while (!/event: permission-request\n(?:[^\n]+\n)+\n/.test(text)) {
      const { value, done } = await reader.read();
      if (done) throw new Error(`Stream ended before the permission request: ${text}`);
      text += value;
    }
    const permission = sse(text).find((e) => e.event === "permission-request")!.data;
    expect(permission.request).toMatchObject({ kind: "custom-tool", toolName: "saveNote" });

    const answer = await fetch(`${base}/api/conversations/c1/permissions/${permission.requestId}`, {
      method: "POST",
      headers: { ...headers, "content-type": "application/json" },
      body: JSON.stringify({ approved: true }),
    });
    expect(answer.status).toBe(204);

    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      text += value;
    }
    const events = sse(text).map((e) => e.event);
    expect(events).toEqual(expect.arrayContaining(["turn-start", "tool-start", "permission-request", "permission-resolved", "tool-end", "done"]));
    expect(notes.list("ada")).toMatchObject([{ title: "streams", body: "Use pipeline()." }]);
    expect(runtime.toolCalls).toMatchObject([{ name: "saveNote", approved: true }]);
  });

  it("serves useChat with tool approvals, per user", async () => {
    const { http, runtime, notes } = await boot((rt) =>
      rt.onPrompt(/remember/).callTool("saveNote", { title: "esm", body: "require(esm) works on Node 22.12+" }).thenReply("Noted."),
    );
    const first = user("u1", "Please remember that require(esm) works on Node 22.12+");
    const pending = await http.post("/api/chat").set("x-user-id", "ada").send({ id: "chat-1", messages: [first] }).expect(200);
    expect(pending.headers["content-type"]).toContain("text/event-stream");
    const chunks = sse(pending.text).map((e) => e.data).filter((d) => d !== "[DONE]");
    expect(chunks.map((c) => c.type)).toEqual(["start", "tool-input-available", "tool-approval-request", "finish"]);
    expect(notes.list("ada")).toEqual([]);

    const start = chunks.find((c) => c.type === "start");
    const input = chunks.find((c) => c.type === "tool-input-available");
    const approval = chunks.find((c) => c.type === "tool-approval-request");
    // What useChat sends after `addToolApprovalResponse({ id, approved: true })`.
    const answered: UIMessage = {
      id: start.messageId,
      role: "assistant",
      parts: [
        { type: "tool-saveNote", toolCallId: input.toolCallId, state: "approval-responded", input: input.input, approval: { id: approval.approvalId, approved: true } },
      ],
    };
    const resumed = await http.post("/api/chat").set("x-user-id", "ada").send({ id: "chat-1", messages: [first, answered] }).expect(200);
    const resumedChunks = sse(resumed.text).map((e) => e.data).filter((d) => d !== "[DONE]");
    expect(resumedChunks.map((c) => c.type)).toContain("tool-output-available");
    expect(resumedChunks.filter((c) => c.type === "text-delta").map((c) => c.delta).join("")).toBe("Noted.");

    expect(notes.list("ada")).toMatchObject([{ title: "esm" }]);
    expect(notes.list("grace")).toEqual([]);
    expect(runtime.toolCalls).toMatchObject([{ name: "saveNote", approved: true }]);
    const list = await http.get("/api/notes").set("x-user-id", "ada").expect(200);
    expect(list.body).toHaveLength(1);
  });
});
