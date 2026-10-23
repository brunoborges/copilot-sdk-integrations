import "reflect-metadata";
import { Test, type TestingModule } from "@nestjs/testing";
import { defineAgent, type Copilot } from "@copilot-sdk-integrations/agent-core";
import { afterEach, describe, expect, it, vi } from "vitest";
import { COPILOT, CopilotSessionCleanup, CopilotSessionsService } from "../src/index.js";
import { CopilotTestRuntime, CopilotTestingModule } from "../src/testing/index.js";

const support = defineAgent({ name: "support", instructions: "Be brief." });
const HOUR = 3_600_000;

let moduleRef: TestingModule | undefined;
afterEach(async () => {
  await moduleRef?.close();
  moduleRef = undefined;
  vi.useRealTimers();
});

async function compile(options: Parameters<typeof CopilotTestingModule.forRoot>[1] = {}): Promise<TestingModule> {
  moduleRef = await Test.createTestingModule({
    imports: [CopilotTestingModule.forRoot((rt) => rt.onPrompt(/.*/).reply("Hi!"), options)],
  }).compile();
  await moduleRef.init();
  return moduleRef;
}

describe("CopilotSessionsService", () => {
  it("lists, reads, and deletes sessions", async () => {
    const ref = await compile();
    const copilot = ref.get<Copilot>(COPILOT);
    const sessions = ref.get(CopilotSessionsService);
    await copilot.agent(support).send({ prompt: "hello", conversationId: "c1" });
    const id = sessions.sessionId(support, "c1");

    expect((await sessions.list()).map((s) => s.sessionId)).toEqual([id]);
    expect(await sessions.exists(id)).toBe(true);
    expect((await sessions.get(id))?.summary).toBe("hello");
    expect((await sessions.getMessages(id))?.map((m) => [m.role, m.content])).toEqual([
      ["user", "hello"],
      ["assistant", "Hi!"],
    ]);
    expect(await sessions.delete(id)).toBe(true);
    expect(await sessions.delete(id)).toBe(false);
    expect(await sessions.list()).toEqual([]);
  });
});

describe("CopilotSessionCleanup", () => {
  it("is disabled by default", async () => {
    const setInterval = vi.spyOn(globalThis, "setInterval");
    const ref = await compile();
    const cleanup = ref.get(CopilotSessionCleanup);
    expect(cleanup.enabled).toBe(false);
    expect(cleanup.settings).toBeUndefined();
    expect(await cleanup.sweep()).toBe(0);
    expect(setInterval.mock.calls.some(([, ms]) => ms === HOUR / 2)).toBe(false);
    setInterval.mockRestore();
  });

  it("validates the option", async () => {
    await expect(compile({ sessionCleanup: { maxAge: -1 } })).rejects.toThrow(/sessionCleanup\.maxAge/);
    await expect(compile({ sessionCleanup: { maxAge: HOUR, every: 1 } as never })).rejects.toThrow(/sessionCleanup/);
  });

  it("sweeps expired sessions on its interval and stops on close", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    const ref = await compile({ sessionCleanup: { maxAge: HOUR, interval: 60_000 } });
    const copilot = ref.get<Copilot>(COPILOT);
    const runtime = ref.get(CopilotTestRuntime);
    const cleanup = ref.get(CopilotSessionCleanup);
    expect(cleanup.settings).toEqual({ maxAge: HOUR, interval: 60_000 });

    await copilot.agent(support).send({ prompt: "hello", conversationId: "c1" });
    const id = copilot.sessions.sessionId(support, "c1");
    await vi.advanceTimersByTimeAsync(60_000);
    expect(runtime.sessionIds).toEqual([id]);

    runtime.setModifiedTime(id, new Date(Date.now() - 2 * HOUR));
    await vi.advanceTimersByTimeAsync(60_000);
    await vi.waitFor(() => expect(runtime.sessionIds).toEqual([]));

    const sweep = vi.spyOn(copilot.sessions, "deleteExpired");
    await moduleRef!.close();
    moduleRef = undefined;
    await vi.advanceTimersByTimeAsync(120_000);
    expect(sweep).not.toHaveBeenCalled();
  });

  it("defaults the interval and never starts the runtime", async () => {
    const ref = await compile({ sessionCleanup: { maxAge: 30 * 60_000 } });
    const cleanup = ref.get(CopilotSessionCleanup);
    expect(cleanup.settings).toEqual({ maxAge: 30 * 60_000, interval: 15 * 60_000 });
    expect(await cleanup.sweep()).toBe(0);
    expect(ref.get(CopilotTestRuntime).started).toBe(false);
  });
});
