import { defineAgent, type CopilotAgentEvent } from "@copilot-sdk-integrations/agent-core";
import { parseSse } from "@copilot-sdk-integrations/agent-core/testing";
import { request } from "node:http";
import { describe, expect, it } from "vitest";
import { setup, waitFor } from "./helpers.js";

const support = defineAgent({ name: "support" });

describe("reply.copilotEvents", () => {
  it("streams a turn as SSE frames with SSE headers", async () => {
    const { fastify } = await setup({ agents: [support] }, { script: (rt) => rt.onPrompt(/.*/).streamReply(["Hel", "lo"]) });
    fastify.get("/stream", (request, reply) => reply.copilotEvents(request.copilot.agent("support").stream({ prompt: "hi" })));
    const res = await fastify.inject("/stream");
    expect(res.statusCode).toBe(200);
    expect(res.headers["content-type"]).toBe("text/event-stream; charset=utf-8");
    expect(res.headers["cache-control"]).toBe("no-cache");
    expect(res.headers["x-accel-buffering"]).toBe("no");
    const frames = parseSse(res.body);
    expect(frames.map((f) => f.event)).toContain("text-delta");
    expect(frames.filter((f) => f.event === "text-delta").map((f) => f.data.delta).join("")).toBe("Hello");
    expect(frames.at(-1)).toMatchObject({ event: "done", data: { type: "done", text: "Hello" } });
    for (const frame of frames) expect(frame.data.type).toBe(frame.event);
  });

  it("sends heartbeat comments", async () => {
    const { fastify } = await setup({ agents: [support] }, { script: (rt) => rt.onPrompt(/.*/).delay(60).reply("ok") });
    fastify.get("/stream", (request, reply) =>
      reply.copilotEvents(request.copilot.agent("support").stream({ prompt: "hi" }), { heartbeatMs: 10 }),
    );
    const res = await fastify.inject("/stream");
    expect(res.body).toContain(": heartbeat\n\n");
    expect(parseSse(res.body).at(-1)?.event).toBe("done");
  });

  it("ends with an error frame when the iteration throws", async () => {
    const { fastify } = await setup();
    async function* failing(): AsyncGenerator<CopilotAgentEvent> {
      yield { type: "text-delta", delta: "partial" };
      throw new Error("internal detail");
    }
    fastify.get("/stream", (_request, reply) => reply.copilotEvents(failing(), { heartbeatMs: 0 }));
    const res = await fastify.inject("/stream");
    expect(res.statusCode).toBe(200);
    expect(parseSse(res.body)).toEqual([
      { event: "text-delta", data: { type: "text-delta", delta: "partial" } },
      { event: "error", data: { type: "error", error: { name: "Error", code: "ERROR", message: "An error occurred." } } },
    ]);
  });

  it("sends errors before the first event to the error handler", async () => {
    const { fastify } = await setup({ agents: [support] }, { script: (rt) => rt.failStart("no runtime") });
    fastify.get("/stream", (request, reply) => reply.copilotEvents(request.copilot.agent("support").stream({ prompt: "hi" })));
    async function* throwing(): AsyncGenerator<CopilotAgentEvent> {
      throw new Error("before start");
    }
    fastify.get("/throw", (_request, reply) => reply.copilotEvents(throwing()));
    const res = await fastify.inject("/stream");
    expect(res.statusCode).toBe(503);
    expect(res.headers["content-type"]).toContain("application/json");
    expect(res.json()).toMatchObject({ code: "COPILOT_RUNTIME_UNAVAILABLE" });
    expect((await fastify.inject("/throw")).statusCode).toBe(500);
  });

  it("reports turn errors as error events", async () => {
    const { fastify } = await setup({ agents: [support] }, { script: (rt) => rt.onPrompt(/.*/).fail("model exploded") });
    fastify.get("/stream", (request, reply) => reply.copilotEvents(request.copilot.agent("support").stream({ prompt: "hi" })));
    const frames = parseSse((await fastify.inject("/stream")).body);
    expect(frames.some((f) => f.event === "error")).toBe(true);
  });

  it("aborts the turn when the client disconnects", async () => {
    const { fastify, test } = await setup({ agents: [support] }, { script: (rt) => rt.onPrompt(/.*/).delay(5_000).reply("never") });
    let signal: AbortSignal | undefined;
    fastify.get("/stream", async (request, reply) => {
      signal = request.copilot.signal;
      return reply.copilotEvents(request.copilot.agent("support").stream({ prompt: "hi" }), { heartbeatMs: 5 });
    });
    const address = await fastify.listen({ port: 0, host: "127.0.0.1" });
    const client = request(`${address}/stream`);
    client.on("error", () => undefined);
    client.on("response", (res) => res.once("data", () => client.destroy()));
    client.end();
    await waitFor(() => test.runtime.prompts.length === 1);
    await waitFor(() => signal?.aborted === true);
    await waitFor(() => test.copilot.capacity().activeTurns === 0);
    expect(test.runtime.abortCount).toBeGreaterThanOrEqual(1);
  });

  it("does not abort a completed request", async () => {
    const { fastify } = await setup({ agents: [support] }, { script: (rt) => rt.onPrompt(/.*/).reply("ok") });
    let signal: AbortSignal | undefined;
    fastify.post("/send", async (request) => {
      signal = request.copilot.signal;
      return request.copilot.agent("support").send({ prompt: "hi" });
    });
    const address = await fastify.listen({ port: 0, host: "127.0.0.1" });
    const res = await fetch(`${address}/send`, { method: "POST", body: JSON.stringify({ a: 1 }), headers: { "content-type": "application/json" } });
    expect(await res.text()).toBe("ok");
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(signal?.aborted).toBe(false);
  });
});
