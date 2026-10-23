import { afterEach, describe, expect, it, vi } from "vitest";
import {
  COPILOT_SESSION_ID_PREFIX,
  CopilotConfigurationError,
  createSessionCleanup,
  defineAgent,
  deriveSessionId,
  runWithCopilotContext,
  sessionCleanupInterval,
} from "../src/index.js";
import { createTestCopilot, silentLogger, type TestCopilot } from "../src/testing/index.js";
import type { ClientLike, CopilotLogger } from "../src/types.js";
import { createCopilot } from "../src/client.js";

const agent = defineAgent({ name: "support", instructions: "Be brief." });
const HOUR = 3_600_000;

let test: TestCopilot | undefined;
afterEach(async () => {
  await test?.stop();
  test = undefined;
  vi.useRealTimers();
});

function setup(options: Parameters<typeof createTestCopilot>[0] = {}): TestCopilot {
  test = createTestCopilot({ script: (rt) => rt.onPrompt(/.*/).reply("Hello!"), ...options });
  return test;
}

describe("copilot.sessions", () => {
  it("lists local sessions, most recently modified first", async () => {
    const { copilot, runtime } = setup();
    await copilot.agent(agent).send({ prompt: "one", conversationId: "c1" });
    await copilot.agent(agent).send({ prompt: "two", conversationId: "c2" });
    const first = copilot.sessions.sessionId(agent, "c1");
    const second = copilot.sessions.sessionId("support", "c2");
    runtime.setModifiedTime(first, new Date(Date.now() - 1000));
    runtime.setModifiedTime(second, new Date(Date.now() - 5000));

    const list = await copilot.sessions.list();
    expect(list.map((s) => s.sessionId)).toEqual([first, second]);
    expect(list[0]).toMatchObject({ isRemote: false, summary: "one" });
    expect(list[0]!.modifiedTime).toBeInstanceOf(Date);
  });

  it("excludes remote sessions and normalizes string dates", async () => {
    const client = fakeClient([
      { sessionId: "copilot-a-1", startTime: "2020-01-01T00:00:00Z", modifiedTime: "2020-01-02T00:00:00Z", isRemote: false },
      { sessionId: "copilot-a-2", startTime: new Date(), modifiedTime: new Date(), isRemote: true },
    ]);
    const copilot = createCopilot({ clientFactory: () => client, logger: silentLogger() });
    const list = await copilot.sessions.list();
    expect(list).toHaveLength(1);
    expect(list[0]!.modifiedTime.toISOString()).toBe("2020-01-02T00:00:00.000Z");
    await copilot.stop();
  });

  it("gets, checks existence, and maps conversation ids like deriveSessionId", async () => {
    const { copilot } = setup();
    await runWithCopilotContext({ principal: "alice" }, () => copilot.agent(agent).send({ prompt: "hi", conversationId: "c1" }));
    const id = deriveSessionId("support", "alice", "c1");
    expect(copilot.sessions.sessionId(agent, "c1", { principal: "alice" })).toBe(id);
    expect(runWithCopilotContext({ principal: "alice" }, () => copilot.sessions.sessionId(agent, "c1"))).toBe(id);
    expect(copilot.sessions.sessionId(agent, "c1")).not.toBe(id);

    expect(await copilot.sessions.exists(id)).toBe(true);
    expect((await copilot.sessions.get(id))?.sessionId).toBe(id);
    expect(await copilot.sessions.exists("missing")).toBe(false);
    expect(await copilot.sessions.get("missing")).toBeUndefined();
    await expect(copilot.sessions.get(" ")).rejects.toThrow(TypeError);
  });

  it("reads user and assistant messages without resuming the session", async () => {
    const { copilot, runtime } = setup({
      script: (rt) =>
        rt
          .onPrompt("first")
          .emit({ type: "assistant.message", agentId: "sub-1", data: { messageId: "s1", content: "sub-agent text" } } as never)
          .reply("Answer one")
          .onPrompt(/.*/)
          .reply("Answer two"),
    });
    await copilot.agent(agent).send({ prompt: "first", conversationId: "c1" });
    await copilot.agent(agent).send({ prompt: "second", conversationId: "c1" });
    const id = copilot.sessions.sessionId(agent, "c1");
    const resumed = runtime.resumedSessions.length;

    const messages = await copilot.sessions.getMessages(id);
    expect(messages?.map((m) => [m.role, m.content])).toEqual([
      ["user", "first"],
      ["assistant", "Answer one"],
      ["user", "second"],
      ["assistant", "Answer two"],
    ]);
    expect(messages![0]!.timestamp).toBeInstanceOf(Date);
    expect(messages![0]!.event.type).toBe("user.message");
    expect(runtime.resumedSessions.length).toBe(resumed);
    expect(await copilot.sessions.getMessages("missing")).toBeUndefined();
  });

  it("pages persisted events and skips sub-agent, tool-call, and empty messages", async () => {
    const events = [
      event("user.message", { content: "Hi" }),
      event("assistant.message", { messageId: "m1", content: "", toolRequests: [{ toolCallId: "t" }] }),
      event("tool.execution_start", { toolCallId: "t" }),
      event("assistant.message", { messageId: "m2", content: "from sub-agent" }, { agentId: "sub" }),
      event("assistant.message", { messageId: "m3", content: "nested", parentToolCallId: "t" }),
      event("assistant.message", { messageId: "m4", content: "Done" }),
    ];
    const reads: Array<string | undefined> = [];
    const client = fakeClient([{ sessionId: "s", startTime: new Date(), modifiedTime: new Date(), isRemote: false }], {
      readPersistedEvents: async ({ cursor }: { cursor?: string }) => {
        reads.push(cursor);
        const start = cursor ? Number(cursor) : 0;
        const end = Math.min(events.length, start + 2);
        return { events: events.slice(start, end), cursor: String(end), hasMore: end < events.length, cursorStatus: "ok" };
      },
    });
    const copilot = createCopilot({ clientFactory: () => client, logger: silentLogger() });
    const messages = await copilot.sessions.getMessages("s");
    expect(messages?.map((m) => [m.role, m.content, m.messageId])).toEqual([
      ["user", "Hi", undefined],
      ["assistant", "Done", "m4"],
    ]);
    expect(reads).toEqual([undefined, "2", "4"]);
    await copilot.stop();
  });

  it("fails when a persisted read cursor expires", async () => {
    const client = fakeClient([{ sessionId: "s", startTime: new Date(), modifiedTime: new Date(), isRemote: false }], {
      readPersistedEvents: async () => ({ events: [], cursor: "x", hasMore: false, cursorStatus: "expired" }),
    });
    const copilot = createCopilot({ clientFactory: () => client, logger: silentLogger() });
    await expect(copilot.sessions.getMessages("s")).rejects.toThrow(/changed while it was being read/);
    await copilot.stop();
  });

  it("deletes a session and reports whether it existed", async () => {
    const { copilot, runtime } = setup();
    await copilot.agent(agent).send({ prompt: "hi", conversationId: "c1" });
    const id = copilot.sessions.sessionId(agent, "c1");
    expect(await copilot.sessions.delete(id)).toBe(true);
    expect(runtime.sessionIds).not.toContain(id);
    expect(await copilot.sessions.delete(id)).toBe(false);
  });

  it("waits for a turn in flight before deleting", async () => {
    const { copilot, runtime } = setup({ script: (rt) => rt.onPrompt(/.*/).delay(100).reply("Slow") });
    await copilot.agent(agent).send({ prompt: "hi", conversationId: "c1" });
    const id = copilot.sessions.sessionId(agent, "c1");
    const turn = copilot.agent(agent).send({ prompt: "again", conversationId: "c1" });
    await vi.waitFor(() => expect(runtime.prompts).toContain("again"));
    const deleted = copilot.sessions.delete(id);
    expect(await turn).toBe("Slow");
    expect(await deleted).toBe(true);
    expect(runtime.sessionIds).not.toContain(id);
  });
});

describe("copilot.sessions.deleteExpired", () => {
  it("never starts the runtime", async () => {
    const { copilot, runtime } = setup();
    expect(await copilot.sessions.deleteExpired(HOUR)).toBe(0);
    expect(runtime.started).toBe(false);
    expect(copilot.state).toBe("not-started");
  });

  it("deletes only old, local sessions created by this library", async () => {
    const old = new Date(Date.now() - 2 * HOUR);
    const client = fakeClient([
      { sessionId: "copilot-a-old", startTime: old, modifiedTime: old, isRemote: false },
      { sessionId: "copilot-a-new", startTime: old, modifiedTime: new Date(), isRemote: false },
      { sessionId: "copilot-a-remote", startTime: old, modifiedTime: old, isRemote: true },
      { sessionId: "cli-session", startTime: old, modifiedTime: old, isRemote: false },
    ]);
    const copilot = createCopilot({ clientFactory: () => client, logger: silentLogger() });
    await copilot.start();
    expect(await copilot.sessions.deleteExpired(HOUR)).toBe(1);
    expect(client.deleteSession).toHaveBeenCalledTimes(1);
    expect(client.deleteSession).toHaveBeenCalledWith("copilot-a-old");
    await copilot.stop();
  });

  it("re-checks metadata and skips sessions modified meanwhile", async () => {
    const old = new Date(Date.now() - 2 * HOUR);
    const client = fakeClient([{ sessionId: "copilot-a-1", startTime: old, modifiedTime: old, isRemote: false }]);
    client.getSessionMetadata = vi.fn(async (id: string) => ({ sessionId: id, startTime: old, modifiedTime: new Date(), isRemote: false }));
    const copilot = createCopilot({ clientFactory: () => client, logger: silentLogger() });
    await copilot.start();
    expect(await copilot.sessions.deleteExpired(HOUR)).toBe(0);
    expect(client.deleteSession).not.toHaveBeenCalled();
    await copilot.stop();
  });

  it("skips sessions with a turn in flight, including ephemeral ones", async () => {
    const { copilot, runtime } = setup({ script: (rt) => rt.onPrompt(/.*/).delay(100).reply("Slow") });
    const conversation = copilot.agent(agent).send({ prompt: "hi", conversationId: "c1" });
    const ephemeral = copilot.agent(agent).send({ prompt: "eph" });
    await vi.waitFor(() => expect(runtime.sessionIds).toHaveLength(2));
    for (const id of runtime.sessionIds) runtime.setModifiedTime(id, new Date(Date.now() - 2 * HOUR));
    expect(await copilot.sessions.deleteExpired(HOUR)).toBe(0);
    await Promise.all([conversation, ephemeral]);

    const id = copilot.sessions.sessionId(agent, "c1");
    runtime.setModifiedTime(id, new Date(Date.now() - 2 * HOUR));
    expect(await copilot.sessions.deleteExpired(HOUR)).toBe(1);
    expect(runtime.sessionIds).toEqual([]);
  });

  it("logs per-session failures and continues", async () => {
    const old = new Date(Date.now() - 2 * HOUR);
    const client = fakeClient([
      { sessionId: "copilot-a-1", startTime: old, modifiedTime: old, isRemote: false },
      { sessionId: "copilot-a-2", startTime: old, modifiedTime: old, isRemote: false },
    ]);
    client.deleteSession.mockImplementationOnce(async () => {
      throw new Error("disk full");
    });
    const logger = silentLogger();
    const warn = vi.spyOn(logger, "warn");
    const copilot = createCopilot({ clientFactory: () => client, logger });
    await copilot.start();
    expect(await copilot.sessions.deleteExpired(HOUR)).toBe(1);
    expect(warn).toHaveBeenCalledWith("Could not delete expired Copilot session", { sessionId: "copilot-a-1", error: "disk full" });
    await copilot.stop();
  });

  it("skips sessions whose cross-replica lease is held", async () => {
    const old = new Date(Date.now() - 2 * HOUR);
    const client = fakeClient([{ sessionId: "copilot-a-1", startTime: old, modifiedTime: old, isRemote: false }]);
    const store = { acquireLease: vi.fn(async () => undefined), get: vi.fn(), set: vi.fn(), delete: vi.fn() };
    const copilot = createCopilot({ clientFactory: () => client, logger: silentLogger(), store });
    await copilot.start();
    expect(await copilot.sessions.deleteExpired(HOUR)).toBe(0);
    expect(store.acquireLease).toHaveBeenCalledWith("copilot:conversation:copilot-a-1", expect.any(Number), 0);
    expect(client.deleteSession).not.toHaveBeenCalled();
    await copilot.stop();
  });

  it("rejects a non-positive max age", async () => {
    const { copilot } = setup();
    await expect(copilot.sessions.deleteExpired(0)).rejects.toThrow(CopilotConfigurationError);
  });
});

describe("createSessionCleanup", () => {
  it("defaults the interval to half the max age, clamped to [1 min, 1 h]", () => {
    expect(sessionCleanupInterval(10_000)).toBe(60_000);
    expect(sessionCleanupInterval(30 * 60_000)).toBe(15 * 60_000);
    expect(sessionCleanupInterval(7 * 24 * HOUR)).toBe(HOUR);
    const { copilot } = setup();
    expect(createSessionCleanup(copilot.sessions, { maxAge: 30 * 60_000 }).interval).toBe(15 * 60_000);
    expect(createSessionCleanup(copilot.sessions, { maxAge: HOUR, interval: 5_000 }).interval).toBe(5_000);
  });

  it("validates options", () => {
    const { copilot } = setup();
    expect(() => createSessionCleanup(copilot.sessions, { maxAge: 0 })).toThrow(/sessionCleanup.maxAge/);
    expect(() => createSessionCleanup(copilot.sessions, { maxAge: HOUR, interval: -1 })).toThrow(/sessionCleanup.interval/);
  });

  it("sweeps on an unref'd timer, logs deletions and failures, and stops", async () => {
    vi.useFakeTimers();
    const deleteExpired = vi.fn<(maxAge: number) => Promise<number>>().mockResolvedValueOnce(2).mockRejectedValueOnce(new Error("boom")).mockResolvedValue(0);
    const logger: CopilotLogger = silentLogger();
    const info = vi.spyOn(logger, "info");
    const warn = vi.spyOn(logger, "warn");
    const cleanup = createSessionCleanup({ deleteExpired } as never, { maxAge: HOUR, interval: 1_000 }, logger);
    cleanup.start();
    cleanup.start();
    await vi.advanceTimersByTimeAsync(1_000);
    expect(deleteExpired).toHaveBeenCalledWith(HOUR);
    expect(info).toHaveBeenCalledWith("Deleted expired Copilot sessions", { deleted: 2, maxAgeMs: HOUR });
    await vi.advanceTimersByTimeAsync(1_000);
    expect(warn).toHaveBeenCalledWith("Copilot session cleanup failed; retrying at the next sweep", { error: "boom" });
    await vi.advanceTimersByTimeAsync(1_000);
    expect(deleteExpired).toHaveBeenCalledTimes(3);
    cleanup.stop();
    await vi.advanceTimersByTimeAsync(5_000);
    expect(deleteExpired).toHaveBeenCalledTimes(3);
  });

  it("sweep() deletes expired sessions through the store", async () => {
    const { copilot, runtime } = setup();
    await copilot.agent(agent).send({ prompt: "hi", conversationId: "c1" });
    const id = copilot.sessions.sessionId(agent, "c1");
    expect(id.startsWith(COPILOT_SESSION_ID_PREFIX)).toBe(true);
    const cleanup = createSessionCleanup(copilot.sessions, { maxAge: HOUR });
    expect(await cleanup.sweep()).toBe(0);
    runtime.setModifiedTime(id, new Date(Date.now() - 2 * HOUR));
    expect(await cleanup.sweep()).toBe(1);
    expect(await copilot.sessions.exists(id)).toBe(false);
  });
});

function event(type: string, data: Record<string, unknown>, extra: Record<string, unknown> = {}) {
  return { id: `${type}-${Math.random()}`, timestamp: new Date().toISOString(), parentId: null, type, data, ...extra };
}

function fakeClient(sessions: Array<Record<string, unknown>>, sessionsRpc: Record<string, unknown> = {}) {
  const store = new Map(sessions.map((s) => [s.sessionId as string, s]));
  const client = {
    start: vi.fn(async () => {}),
    stop: vi.fn(async () => []),
    createSession: vi.fn(),
    resumeSession: vi.fn(),
    deleteSession: vi.fn(async (id: string) => {
      store.delete(id);
    }),
    getSessionMetadata: vi.fn(async (id: string) => store.get(id)) as (id: string) => Promise<unknown>,
    listSessions: vi.fn(async () => [...store.values()]),
    rpc: { sessions: sessionsRpc },
    isConnected: () => true,
  };
  return client as typeof client & ClientLike;
}
