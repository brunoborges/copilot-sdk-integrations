import type { SessionStore } from "../types.js";

interface Lease {
  token: number;
  expiresAt: number;
}

interface Entry {
  value: string;
  expiresAt?: number;
}

/**
 * Single-process `SessionStore`: exclusive leases with TTL, and a key/value map with optional TTL.
 * Use it for tests and single-replica deployments; use a shared store (e.g. Redis) across replicas.
 */
export function memoryStore(): SessionStore {
  const leases = new Map<string, Lease>();
  const values = new Map<string, Entry>();
  const waiters = new Map<string, Set<() => void>>();
  let nextToken = 1;

  const wake = (key: string) => {
    const set = waiters.get(key);
    if (!set) return;
    waiters.delete(key);
    for (const w of set) w();
  };

  const tryTake = (key: string, ttlMs: number): (() => Promise<void>) | undefined => {
    const now = Date.now();
    const current = leases.get(key);
    if (current && current.expiresAt > now) return undefined;
    const token = nextToken++;
    leases.set(key, { token, expiresAt: now + ttlMs });
    return async () => {
      if (leases.get(key)?.token === token) {
        leases.delete(key);
        wake(key);
      }
    };
  };

  return {
    async acquireLease(key, ttlMs, waitMs) {
      if (!(ttlMs > 0)) throw new RangeError("ttlMs must be positive");
      const deadline = Date.now() + Math.max(0, waitMs);
      for (;;) {
        const release = tryTake(key, ttlMs);
        if (release) return release;
        const now = Date.now();
        if (now >= deadline) return undefined;
        const expiresAt = leases.get(key)?.expiresAt ?? now;
        const delay = Math.max(1, Math.min(deadline, expiresAt) - now);
        await new Promise<void>((resolve) => {
          let set = waiters.get(key);
          if (!set) waiters.set(key, (set = new Set()));
          const done = () => {
            clearTimeout(timer);
            set!.delete(done);
            resolve();
          };
          const timer = setTimeout(done, delay);
          set.add(done);
        });
      }
    },
    async get(key) {
      const entry = values.get(key);
      if (!entry) return undefined;
      if (entry.expiresAt !== undefined && entry.expiresAt <= Date.now()) {
        values.delete(key);
        return undefined;
      }
      return entry.value;
    },
    async set(key, value, ttlMs) {
      values.set(key, { value, expiresAt: ttlMs !== undefined && ttlMs > 0 ? Date.now() + ttlMs : undefined });
    },
    async delete(key) {
      values.delete(key);
    },
  };
}
