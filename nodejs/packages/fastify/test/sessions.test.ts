import { defineAgent } from "@copilot-sdk-integrations/agent-core";
import type { CreateTestCopilotOptions } from "@copilot-sdk-integrations/agent-core/testing";
import { afterEach, describe, expect, it, vi } from "vitest";
import { setup } from "./helpers.js";

const support = defineAgent({ name: "support", instructions: "Help." });
const HOUR = 3_600_000;
const reply: CreateTestCopilotOptions = { script: (rt) => rt.onPrompt(/.*/).reply("Hi!") };

afterEach(() => {
  vi.useRealTimers();
});

describe("fastify.copilotSessions", () => {
  it("lists, reads, and deletes sessions", async () => {
    const { fastify } = await setup({ agents: [support] }, reply);
    await fastify.ready();
    expect(fastify.copilotSessions).toBe(fastify.copilot.core.sessions);
    expect(fastify.copilot.sessions).toBe(fastify.copilotSessions);

    await fastify.copilot.agent("support").send({ prompt: "hello", conversationId: "c1" });
    const id = fastify.copilotSessions.sessionId(support, "c1");
    expect((await fastify.copilotSessions.list()).map((s) => s.sessionId)).toEqual([id]);
    expect((await fastify.copilotSessions.getMessages(id))?.map((m) => m.content)).toEqual(["hello", "Hi!"]);
    expect(await fastify.copilotSessions.delete(id)).toBe(true);
    expect(await fastify.copilotSessions.exists(id)).toBe(false);
  });
});

describe("sessionCleanup", () => {
  it("is disabled by default", async () => {
    const { fastify } = await setup();
    await fastify.ready();
    expect(fastify.copilotSessionCleanup).toBeNull();
  });

  it.each([[{ maxAge: 0 }], [{ maxAge: HOUR, interval: -5 }], [{ maxAge: HOUR, every: 1 }]])("rejects %j", async (sessionCleanup) => {
    await expect(setup({ sessionCleanup: sessionCleanup as never })).rejects.toThrow(/sessionCleanup/);
  });

  it("defaults the interval to half the max age", async () => {
    const { fastify } = await setup({ sessionCleanup: { maxAge: 30 * 60_000 } });
    expect(fastify.copilotSessionCleanup).toMatchObject({ maxAge: 30 * 60_000, interval: 15 * 60_000 });
  });

  it("sweeps from onReady until onClose and never starts the runtime", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const { fastify, test } = await setup({ agents: [support], sessionCleanup: { maxAge: HOUR, interval: 60_000 } }, reply);
    const sweep = vi.spyOn(test.copilot.sessions, "deleteExpired");
    await vi.advanceTimersByTimeAsync(60_000);
    expect(sweep).not.toHaveBeenCalled();

    await fastify.ready();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(sweep).toHaveBeenCalledWith(HOUR);
    expect(test.runtime.started).toBe(false);

    await fastify.copilot.agent("support").send({ prompt: "hello", conversationId: "c1" });
    const id = fastify.copilotSessions.sessionId(support, "c1");
    test.runtime.setModifiedTime(id, new Date(Date.now() - 2 * HOUR));
    expect(await fastify.copilotSessionCleanup!.sweep()).toBe(1);
    expect(test.runtime.sessionIds).toEqual([]);

    await fastify.close();
    sweep.mockClear();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(sweep).not.toHaveBeenCalled();
  });
});
