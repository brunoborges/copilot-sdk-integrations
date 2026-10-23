import type { PermissionResponse } from "@copilot-sdk-integrations/agent-core";
import type { TurnPump } from "./ui-stream.js";

export interface CopilotApprovalStoreOptions {
  /**
   * How long a turn suspended on an approval is kept before it is aborted. Default: 600_000 ms.
   * Core's own `approvalWindow` (default 120 s) still decides when an unanswered approval is denied.
   */
  ttlMs?: number;
}

/** @internal Who may answer an approval: the conversation and principal of the request that started the turn. */
export interface ApprovalOwner {
  conversationId: string | undefined;
  principal: string | undefined;
}

interface PendingApproval {
  respond(response: PermissionResponse): void;
  toolCallId: string;
  pump: TurnPump;
  owner: ApprovalOwner;
}

interface SuspendedTurn {
  pump: TurnPump;
  owner: ApprovalOwner;
  timer: ReturnType<typeof setTimeout>;
}

/**
 * Process-local registry of pending tool approvals and of turns suspended while they wait for one.
 *
 * `streamCopilotChat` finishes the HTTP response after a `tool-approval-request`, keeps the Copilot
 * turn running here, and resumes it when the next `useChat` request carries the approval response.
 * With several replicas, route a conversation's requests to the same replica (sticky sessions).
 */
export class CopilotApprovalStore {
  private readonly ttlMs: number;
  private readonly approvals = new Map<string, PendingApproval>();
  private readonly suspended = new Map<TurnPump, SuspendedTurn>();

  constructor(options: CopilotApprovalStoreOptions = {}) {
    this.ttlMs = options.ttlMs ?? 600_000;
  }

  /** Answers a pending approval. Returns false when the approval id is unknown (expired, answered, or another replica). */
  respond(approvalId: string, response: PermissionResponse): boolean {
    const pending = this.approvals.get(approvalId);
    if (!pending) return false;
    pending.respond(response);
    return true;
  }

  /** Whether an approval is pending. */
  has(approvalId: string): boolean {
    return this.approvals.has(approvalId);
  }

  /** Number of turns currently suspended on an approval. */
  get suspendedTurns(): number {
    return this.suspended.size;
  }

  /** Aborts every suspended turn. */
  async clear(): Promise<void> {
    await Promise.all([...this.suspended.keys()].map((pump) => this.abort(pump)));
    this.approvals.clear();
  }

  /** @internal */
  register(approvalId: string, pending: PendingApproval): void {
    this.approvals.set(approvalId, pending);
  }

  /** @internal */
  unregister(approvalId: string): void {
    this.approvals.delete(approvalId);
  }

  /**
   * @internal Returns a pending approval only when `owner` matches the one recorded when it was
   * registered; a mismatch looks exactly like an unknown id, so ids can't be probed.
   */
  get(approvalId: string, owner: ApprovalOwner): PendingApproval | undefined {
    const pending = this.approvals.get(approvalId);
    return pending && sameOwner(pending.owner, owner) ? pending : undefined;
  }

  /** @internal Keeps a turn alive between HTTP requests. */
  suspend(pump: TurnPump, owner: ApprovalOwner): void {
    const timer = setTimeout(() => void this.abort(pump), this.ttlMs);
    timer.unref?.();
    this.suspended.set(pump, { pump, owner, timer });
  }

  /** @internal */
  isSuspended(pump: TurnPump): boolean {
    return this.suspended.has(pump);
  }

  /** @internal Takes a suspended turn for resumption. */
  take(pump: TurnPump): TurnPump | undefined {
    const turn = this.suspended.get(pump);
    if (!turn) return undefined;
    clearTimeout(turn.timer);
    this.suspended.delete(pump);
    return pump;
  }

  /** @internal Aborts the owner's suspended turns of a conversation (a new user message supersedes them). */
  async abortConversation(owner: ApprovalOwner): Promise<void> {
    const pumps = [...this.suspended.values()].filter((t) => sameOwner(t.owner, owner)).map((t) => t.pump);
    await Promise.all(pumps.map((pump) => this.abort(pump)));
  }

  private async abort(pump: TurnPump): Promise<void> {
    if (!this.take(pump)) return;
    for (const [id, pending] of this.approvals) {
      if (pending.pump === pump) this.approvals.delete(id);
    }
    await pump.close();
  }
}

function sameOwner(a: ApprovalOwner, b: ApprovalOwner): boolean {
  return a.conversationId === b.conversationId && a.principal === b.principal;
}

/** Creates a process-local approval store. */
export function createApprovalStore(options?: CopilotApprovalStoreOptions): CopilotApprovalStore {
  return new CopilotApprovalStore(options);
}

/** Store used by `streamCopilotChat` when none is given. */
export const defaultApprovalStore: CopilotApprovalStore = new CopilotApprovalStore();
