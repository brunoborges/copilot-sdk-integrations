import { createHash } from "node:crypto";
import {
  CopilotAbortedError,
  CopilotAdmissionRejectedError,
  CopilotConversationBusyError,
  CopilotShuttingDownError,
} from "./errors.js";

// ---------------------------------------------------------------------------
// Session-id derivation
// ---------------------------------------------------------------------------

/**
 * Prefix of every session id this library creates (conversation and ephemeral sessions). The
 * session cleanup only ever deletes sessions with this prefix.
 */
export const COPILOT_SESSION_ID_PREFIX = "copilot-";

/**
 * Derives the SDK session id for a conversation.
 *
 * The id is a SHA-256 digest of the length-prefixed agent name, principal, and conversation id,
 * so distinct tuples can never collide by concatenation (`("a", "bc")` vs `("ab", "c")`) and an
 * anonymous caller (`principal === undefined`) never shares a session with a principal named `""`.
 * Callers can't resume another principal's conversation because the principal is part of the digest.
 *
 * Format: `copilot-<agentName>-<32 hex chars>` (128 bits of the digest).
 */
export function deriveSessionId(agentName: string, principal: string | undefined, conversationId: string): string {
  if (typeof agentName !== "string" || agentName.length === 0) {
    throw new TypeError("agentName must be a non-empty string");
  }
  if (typeof conversationId !== "string" || conversationId.length === 0) {
    throw new TypeError("conversationId must be a non-empty string");
  }
  const hash = createHash("sha256");
  const part = (tag: string, value: string | undefined) => {
    if (value === undefined) {
      hash.update(`${tag}:-;`);
      return;
    }
    const bytes = Buffer.from(value, "utf8");
    hash.update(`${tag}:${bytes.length}:`);
    hash.update(bytes);
    hash.update(";");
  };
  hash.update("copilot-session-v1;");
  part("agent", agentName);
  part("principal", principal);
  part("conversation", conversationId);
  return `${COPILOT_SESSION_ID_PREFIX}${agentName}-${hash.digest("hex").slice(0, 32)}`;
}

// ---------------------------------------------------------------------------
// Abort helpers
// ---------------------------------------------------------------------------

/** The error a signal was aborted with, normalized to an `Error`. */
export function abortReason(signal: AbortSignal): Error {
  const reason: unknown = signal.reason;
  if (reason instanceof Error && reason.name !== "AbortError") return reason;
  return new CopilotAbortedError();
}

// ---------------------------------------------------------------------------
// Per-conversation mutex
// ---------------------------------------------------------------------------

interface LockWaiter {
  grant(): void;
}

/**
 * In-process keyed mutex: at most one holder per key, FIFO. Waiters give up after `waitMs`
 * (`CopilotConversationBusyError`) or when their signal aborts.
 */
export class ConversationLocks {
  /** Per key: `held` plus FIFO waiters. */
  private readonly entries = new Map<string, { waiters: LockWaiter[] }>();

  /** Number of keys currently held. */
  get size(): number {
    return this.entries.size;
  }

  isLocked(key: string): boolean {
    return this.entries.has(key);
  }

  /** Acquires the key only if it is free right now. Returns its release function, or `undefined` when held. */
  tryAcquire(key: string): (() => void) | undefined {
    if (this.entries.has(key)) return undefined;
    this.entries.set(key, { waiters: [] });
    return this.releaser(key);
  }

  acquire(
    key: string,
    options: { waitMs: number; signal?: AbortSignal; conversationId?: string },
  ): Promise<() => void> {
    const { signal, waitMs } = options;
    if (signal?.aborted) return Promise.reject(abortReason(signal));
    const entry = this.entries.get(key);
    if (!entry) {
      this.entries.set(key, { waiters: [] });
      return Promise.resolve(this.releaser(key));
    }
    return new Promise<() => void>((resolve, reject) => {
      let timer: ReturnType<typeof setTimeout> | undefined;
      const cleanup = () => {
        if (timer) clearTimeout(timer);
        signal?.removeEventListener("abort", onAbort);
      };
      const remove = () => {
        const i = entry.waiters.indexOf(waiter);
        if (i >= 0) entry.waiters.splice(i, 1);
      };
      const waiter: LockWaiter = {
        grant: () => {
          cleanup();
          resolve(this.releaser(key));
        },
      };
      const onAbort = () => {
        remove();
        cleanup();
        reject(abortReason(signal!));
      };
      entry.waiters.push(waiter);
      if (Number.isFinite(waitMs)) {
        timer = setTimeout(() => {
          remove();
          cleanup();
          reject(new CopilotConversationBusyError(options.conversationId ?? key));
        }, Math.max(0, waitMs));
      }
      signal?.addEventListener("abort", onAbort, { once: true });
    });
  }

  private releaser(key: string): () => void {
    let released = false;
    return () => {
      if (released) return;
      released = true;
      const entry = this.entries.get(key);
      if (!entry) return;
      const next = entry.waiters.shift();
      if (next) next.grant();
      else this.entries.delete(key);
    };
  }
}

// ---------------------------------------------------------------------------
// Admission control
// ---------------------------------------------------------------------------

/**
 * A reservation in the admission controller. Created synchronously (rejecting immediately when
 * the controller is full), then `acquire()` waits for an execution slot. Always `release()`.
 */
export interface AdmissionTicket {
  /** Wait for an execution slot. Rejects if the signal aborts or the controller closes while queued. */
  acquire(signal?: AbortSignal): Promise<void>;
  /** Free the slot (if held) and the reservation. Idempotent. */
  release(): void;
}

interface SlotWaiter {
  grant(): void;
  fail(error: Error): void;
}

/**
 * Bounded admission: at most `maxConcurrent` turns execute; at most `maxQueued` further turns wait
 * (for a slot or for their conversation). Beyond that, `reserve()` throws
 * `CopilotAdmissionRejectedError`. Queued waiters leave the queue when their signal aborts.
 */
export class AdmissionController {
  private outstanding = 0;
  private active = 0;
  private readonly waiters: SlotWaiter[] = [];
  private closed = false;
  private idleListeners: Array<() => void> = [];

  constructor(
    readonly maxConcurrent: number,
    readonly maxQueued: number,
  ) {}

  get activeCount(): number {
    return this.active;
  }

  /** Admitted turns that don't hold an execution slot yet. */
  get queuedCount(): number {
    return this.outstanding - this.active;
  }

  get outstandingCount(): number {
    return this.outstanding;
  }

  get isClosed(): boolean {
    return this.closed;
  }

  reserve(): AdmissionTicket {
    if (this.closed) throw new CopilotShuttingDownError();
    if (this.outstanding >= this.maxConcurrent + this.maxQueued) {
      throw new CopilotAdmissionRejectedError();
    }
    this.outstanding++;
    let state: "reserved" | "waiting" | "active" | "released" = "reserved";
    let waiter: SlotWaiter | undefined;
    const ticket: AdmissionTicket = {
      acquire: (signal) => {
        if (state !== "reserved") return Promise.reject(new Error("Admission ticket already used"));
        if (this.closed) return Promise.reject(new CopilotShuttingDownError());
        if (signal?.aborted) return Promise.reject(abortReason(signal));
        if (this.active < this.maxConcurrent && this.waiters.length === 0) {
          this.active++;
          state = "active";
          return Promise.resolve();
        }
        state = "waiting";
        return new Promise<void>((resolve, reject) => {
          const onAbort = () => {
            this.removeWaiter(waiter!);
            state = "reserved";
            reject(abortReason(signal!));
          };
          waiter = {
            grant: () => {
              signal?.removeEventListener("abort", onAbort);
              this.active++;
              state = "active";
              resolve();
            },
            fail: (error) => {
              signal?.removeEventListener("abort", onAbort);
              state = "reserved";
              reject(error);
            },
          };
          this.waiters.push(waiter);
          signal?.addEventListener("abort", onAbort, { once: true });
        });
      },
      release: () => {
        if (state === "released") return;
        if (state === "waiting" && waiter) this.removeWaiter(waiter);
        if (state === "active") {
          this.active--;
          this.grantNext();
        }
        state = "released";
        this.outstanding--;
        if (this.outstanding === 0) this.notifyIdle();
      },
    };
    return ticket;
  }

  /** Stop admitting: new reservations and queued waiters fail with `CopilotShuttingDownError`. */
  close(): void {
    this.closed = true;
    const pending = this.waiters.splice(0);
    for (const w of pending) w.fail(new CopilotShuttingDownError());
  }

  /** Resolves `true` once no turn is outstanding, or `false` after `timeoutMs`. */
  whenIdle(timeoutMs: number): Promise<boolean> {
    if (this.outstanding === 0) return Promise.resolve(true);
    return new Promise((resolve) => {
      const listener = () => {
        clearTimeout(timer);
        resolve(true);
      };
      const timer = setTimeout(() => {
        this.idleListeners = this.idleListeners.filter((l) => l !== listener);
        resolve(false);
      }, Math.max(0, timeoutMs));
      this.idleListeners.push(listener);
    });
  }

  private removeWaiter(waiter: SlotWaiter): void {
    const i = this.waiters.indexOf(waiter);
    if (i >= 0) this.waiters.splice(i, 1);
  }

  private grantNext(): void {
    while (this.active < this.maxConcurrent && this.waiters.length > 0) {
      this.waiters.shift()!.grant();
    }
  }

  private notifyIdle(): void {
    const listeners = this.idleListeners;
    this.idleListeners = [];
    for (const l of listeners) l();
  }
}
