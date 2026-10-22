import { afterEach, describe, expect, it, vi } from "vitest";
import { memoryStore } from "../src/stores/index.js";

afterEach(() => {
  vi.useRealTimers();
});

describe("memoryStore leases", () => {
  it("is exclusive and returns undefined when not acquired within waitMs", async () => {
    const store = memoryStore();
    const release = await store.acquireLease("k", 10_000, 0);
    expect(release).toBeTypeOf("function");
    expect(await store.acquireLease("k", 10_000, 20)).toBeUndefined();
    expect(await store.acquireLease("other", 10_000, 0)).toBeTypeOf("function");
    await release!();
    expect(await store.acquireLease("k", 10_000, 0)).toBeTypeOf("function");
  });

  it("wakes a waiter as soon as the lease is released", async () => {
    const store = memoryStore();
    const release = await store.acquireLease("k", 60_000, 0);
    const started = Date.now();
    const waiting = store.acquireLease("k", 60_000, 5_000);
    setTimeout(() => void release!(), 20);
    const second = await waiting;
    expect(second).toBeTypeOf("function");
    expect(Date.now() - started).toBeLessThan(1_000);
  });

  it("lets an expired lease be taken over, and the stale holder cannot release the new lease", async () => {
    const store = memoryStore();
    const stale = await store.acquireLease("k", 30, 0);
    const next = await store.acquireLease("k", 60_000, 1_000);
    expect(next).toBeTypeOf("function");
    await stale!();
    expect(await store.acquireLease("k", 60_000, 0)).toBeUndefined();
    await next!();
    expect(await store.acquireLease("k", 60_000, 0)).toBeTypeOf("function");
  });

  it("only one of many concurrent waiters wins each release", async () => {
    const store = memoryStore();
    const first = await store.acquireLease("k", 60_000, 0);
    let holders = 0;
    let maxHolders = 0;
    const worker = async () => {
      const release = await store.acquireLease("k", 60_000, 5_000);
      expect(release).toBeDefined();
      holders++;
      maxHolders = Math.max(maxHolders, holders);
      await new Promise((r) => setTimeout(r, 2));
      holders--;
      await release!();
    };
    const workers = Promise.all([worker(), worker(), worker(), worker()]);
    await first!();
    await workers;
    expect(maxHolders).toBe(1);
  });

  it("rejects a non-positive ttl", async () => {
    await expect(memoryStore().acquireLease("k", 0, 0)).rejects.toBeInstanceOf(RangeError);
  });
});

describe("memoryStore values", () => {
  it("get/set/delete", async () => {
    const store = memoryStore();
    expect(await store.get("a")).toBeUndefined();
    await store.set("a", "1");
    expect(await store.get("a")).toBe("1");
    await store.set("a", "2");
    expect(await store.get("a")).toBe("2");
    await store.delete("a");
    expect(await store.get("a")).toBeUndefined();
    await store.delete("missing");
  });

  it("expires values with a ttl", async () => {
    vi.useFakeTimers();
    const store = memoryStore();
    await store.set("a", "1", 100);
    await store.set("b", "2");
    vi.advanceTimersByTime(99);
    expect(await store.get("a")).toBe("1");
    vi.advanceTimersByTime(1);
    expect(await store.get("a")).toBeUndefined();
    expect(await store.get("b")).toBe("2");
  });
});
