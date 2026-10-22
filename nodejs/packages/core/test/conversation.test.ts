import { describe, expect, it } from "vitest";
import { AdmissionController, ConversationLocks, abortReason, deriveSessionId } from "../src/conversation.js";
import {
  CopilotAbortedError,
  CopilotAdmissionRejectedError,
  CopilotConversationBusyError,
  CopilotShuttingDownError,
  CopilotTimeoutError,
} from "../src/errors.js";

const flush = () => new Promise<void>((r) => setImmediate(r));

describe("deriveSessionId", () => {
  it("is deterministic and has the documented shape", () => {
    const a = deriveSessionId("support", "alice", "conv-1");
    expect(a).toBe(deriveSessionId("support", "alice", "conv-1"));
    expect(a).toMatch(/^copilot-support-[0-9a-f]{32}$/);
  });

  it("isolates principals, agents and conversations", () => {
    const base = deriveSessionId("support", "alice", "c");
    expect(deriveSessionId("support", "bob", "c")).not.toBe(base);
    expect(deriveSessionId("billing", "alice", "c")).not.toBe(base);
    expect(deriveSessionId("support", "alice", "d")).not.toBe(base);
  });

  it("distinguishes anonymous from the empty principal", () => {
    expect(deriveSessionId("a", undefined, "c")).not.toBe(deriveSessionId("a", "", "c"));
    expect(deriveSessionId("a", undefined, "c")).toBe(deriveSessionId("a", undefined, "c"));
  });

  it("is not fooled by concatenation ambiguities", () => {
    expect(deriveSessionId("a", "b:c", "d")).not.toBe(deriveSessionId("a", "b", "c:d"));
    expect(deriveSessionId("a", "b;", "c")).not.toBe(deriveSessionId("a", "b", ";c"));
    expect(deriveSessionId("a", "-", "c")).not.toBe(deriveSessionId("a", undefined, "c"));
  });

  it("handles unicode and rejects empty inputs", () => {
    expect(deriveSessionId("a", "zoë", "💬")).toMatch(/^copilot-a-[0-9a-f]{32}$/);
    expect(() => deriveSessionId("", "p", "c")).toThrow(TypeError);
    expect(() => deriveSessionId("a", "p", "")).toThrow(TypeError);
  });
});

describe("abortReason", () => {
  it("keeps integration errors and normalizes DOM aborts", () => {
    const c = new AbortController();
    c.abort();
    expect(abortReason(c.signal)).toBeInstanceOf(CopilotAbortedError);
    const t = new AbortController();
    t.abort(new CopilotTimeoutError(5));
    expect(abortReason(t.signal)).toBeInstanceOf(CopilotTimeoutError);
    const s = new AbortController();
    s.abort("why");
    expect(abortReason(s.signal)).toBeInstanceOf(CopilotAbortedError);
  });
});

describe("ConversationLocks", () => {
  it("grants exclusively and FIFO", async () => {
    const locks = new ConversationLocks();
    const order: number[] = [];
    const r1 = await locks.acquire("k", { waitMs: 1000 });
    const p2 = locks.acquire("k", { waitMs: 1000 }).then((r) => (order.push(2), r));
    const p3 = locks.acquire("k", { waitMs: 1000 }).then((r) => (order.push(3), r));
    await flush();
    expect(order).toEqual([]);
    expect(locks.isLocked("k")).toBe(true);
    r1();
    const r2 = await p2;
    await flush();
    expect(order).toEqual([2]);
    r2();
    (await p3)();
    expect(order).toEqual([2, 3]);
    expect(locks.isLocked("k")).toBe(false);
    expect(locks.size).toBe(0);
  });

  it("does not block other keys", async () => {
    const locks = new ConversationLocks();
    await locks.acquire("a", { waitMs: 10 });
    const rb = await locks.acquire("b", { waitMs: 10 });
    expect(locks.size).toBe(2);
    rb();
  });

  it("release is idempotent (a double release doesn't free a later holder)", async () => {
    const locks = new ConversationLocks();
    const r1 = await locks.acquire("k", { waitMs: 1000 });
    const p2 = locks.acquire("k", { waitMs: 1000 });
    r1();
    const r2 = await p2;
    r1();
    let third = false;
    const p3 = locks.acquire("k", { waitMs: 1000 }).then((r) => ((third = true), r));
    await flush();
    expect(third).toBe(false);
    r2();
    (await p3)();
  });

  it("times out with CopilotConversationBusyError and leaves the queue", async () => {
    const locks = new ConversationLocks();
    const r1 = await locks.acquire("k", { waitMs: 1000 });
    await expect(locks.acquire("k", { waitMs: 10, conversationId: "conv" })).rejects.toBeInstanceOf(
      CopilotConversationBusyError,
    );
    r1();
    expect(locks.isLocked("k")).toBe(false);
  });

  it("abort removes a waiter; already-aborted rejects immediately", async () => {
    const locks = new ConversationLocks();
    const r1 = await locks.acquire("k", { waitMs: 1000 });
    const c = new AbortController();
    const p = locks.acquire("k", { waitMs: 1000, signal: c.signal });
    c.abort(new CopilotTimeoutError(1));
    await expect(p).rejects.toBeInstanceOf(CopilotTimeoutError);
    r1();
    expect(locks.isLocked("k")).toBe(false);
    await expect(locks.acquire("k", { waitMs: 10, signal: c.signal })).rejects.toBeInstanceOf(CopilotTimeoutError);
  });
});

describe("AdmissionController", () => {
  it("limits concurrency and queues FIFO", async () => {
    const ac = new AdmissionController(2, 2);
    const t1 = ac.reserve();
    const t2 = ac.reserve();
    const t3 = ac.reserve();
    const t4 = ac.reserve();
    await t1.acquire();
    await t2.acquire();
    const order: number[] = [];
    const p3 = t3.acquire().then(() => order.push(3));
    const p4 = t4.acquire().then(() => order.push(4));
    await flush();
    expect(ac.activeCount).toBe(2);
    expect(ac.queuedCount).toBe(2);
    t1.release();
    await p3;
    expect(order).toEqual([3]);
    t2.release();
    await p4;
    expect(order).toEqual([3, 4]);
    expect(ac.activeCount).toBe(2);
    t3.release();
    t4.release();
    expect(ac.outstandingCount).toBe(0);
  });

  it("rejects when the queue overflows", () => {
    const ac = new AdmissionController(1, 1);
    ac.reserve();
    ac.reserve();
    expect(() => ac.reserve()).toThrow(CopilotAdmissionRejectedError);
  });

  it("supports a zero-length queue", async () => {
    const ac = new AdmissionController(1, 0);
    const t = ac.reserve();
    expect(() => ac.reserve()).toThrow(CopilotAdmissionRejectedError);
    t.release();
    await ac.reserve().acquire();
  });

  it("removes an aborted waiter from the queue", async () => {
    const ac = new AdmissionController(1, 2);
    const t1 = ac.reserve();
    await t1.acquire();
    const c = new AbortController();
    const t2 = ac.reserve();
    const t3 = ac.reserve();
    const p2 = t2.acquire(c.signal);
    const p3 = t3.acquire();
    c.abort();
    await expect(p2).rejects.toBeInstanceOf(CopilotAbortedError);
    t2.release();
    expect(ac.outstandingCount).toBe(2);
    t1.release();
    await p3;
    expect(ac.activeCount).toBe(1);
    t3.release();
  });

  it("releasing a waiting ticket frees its reservation without granting it", async () => {
    const ac = new AdmissionController(1, 1);
    const t1 = ac.reserve();
    await t1.acquire();
    const t2 = ac.reserve();
    void t2.acquire().catch(() => undefined);
    t2.release();
    expect(ac.outstandingCount).toBe(1);
    t1.release();
    await flush();
    expect(ac.activeCount).toBe(0);
  });

  it("release is idempotent", async () => {
    const ac = new AdmissionController(1, 0);
    const t = ac.reserve();
    await t.acquire();
    t.release();
    t.release();
    expect(ac.outstandingCount).toBe(0);
    expect(ac.activeCount).toBe(0);
  });

  it("close() rejects queued waiters and new reservations; whenIdle waits for outstanding turns", async () => {
    const ac = new AdmissionController(1, 1);
    const t1 = ac.reserve();
    await t1.acquire();
    const t2 = ac.reserve();
    const p2 = t2.acquire();
    ac.close();
    await expect(p2).rejects.toBeInstanceOf(CopilotShuttingDownError);
    expect(() => ac.reserve()).toThrow(CopilotShuttingDownError);
    t2.release();
    expect(await ac.whenIdle(10)).toBe(false);
    const idle = ac.whenIdle(1000);
    t1.release();
    expect(await idle).toBe(true);
    expect(await ac.whenIdle(0)).toBe(true);
  });
});
