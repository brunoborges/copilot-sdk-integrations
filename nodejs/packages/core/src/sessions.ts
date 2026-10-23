import type { SessionEvent } from "@github/copilot-sdk";
import { conversationLeaseKey, isNotFoundError, LEASE_MARGIN_MS } from "./agent.js";
import type { CopilotRuntime } from "./client.js";
import { getCopilotContext } from "./context.js";
import { COPILOT_SESSION_ID_PREFIX, deriveSessionId } from "./conversation.js";
import { CopilotConfigurationError, CopilotConversationBusyError, CopilotIntegrationError } from "./errors.js";
import type { ClientLike, CopilotSessionMessage, CopilotSessionMetadata, CopilotSessions } from "./types.js";

/** Events per `readPersistedEvents` page (the RPC's maximum). */
const PERSISTED_EVENTS_PAGE_SIZE = 1000;

export const DEFAULT_SESSION_CLEANUP_MIN_INTERVAL_MS = 60_000;
export const DEFAULT_SESSION_CLEANUP_MAX_INTERVAL_MS = 3_600_000;

/** The default sweep interval for a maximum session age: half of it, clamped to between one minute and one hour. */
export function sessionCleanupInterval(maxAgeMs: number): number {
  return Math.min(Math.max(maxAgeMs / 2, DEFAULT_SESSION_CLEANUP_MIN_INTERVAL_MS), DEFAULT_SESSION_CLEANUP_MAX_INTERVAL_MS);
}

/** Creates `copilot.sessions`. */
export function createCopilotSessions(runtime: CopilotRuntime): CopilotSessions {
  const fetchMetadata = async (client: ClientLike, sessionId: string): Promise<CopilotSessionMetadata | undefined> => {
    if (client.getSessionMetadata) return toMetadata(await client.getSessionMetadata(sessionId));
    const all = await listAll(client);
    return all.find((s) => s.sessionId === sessionId);
  };

  const withLease = async <T>(sessionId: string, waitMs: number, run: () => Promise<T>): Promise<T | typeof BUSY> => {
    const store = runtime.options.store;
    if (!store) return run();
    const release = await store.acquireLease(conversationLeaseKey(sessionId), runtime.responseTimeout + LEASE_MARGIN_MS, waitMs);
    if (!release) return BUSY;
    try {
      return await run();
    } finally {
      await release().catch((e) => runtime.logger.warn("Failed to release conversation lease", { error: messageOf(e) }));
    }
  };

  const deleteSession = async (client: ClientLike, sessionId: string): Promise<void> => {
    try {
      await client.deleteSession(sessionId);
    } catch (error) {
      if (!isNotFoundError(error)) throw error;
    }
  };

  return {
    async list() {
      const client = await runtime.acquireClient();
      const sessions = (await listAll(client)).filter((s) => !s.isRemote);
      return sessions.sort((a, b) => b.modifiedTime.getTime() - a.modifiedTime.getTime());
    },

    async get(sessionId) {
      requireNonBlank(sessionId);
      return fetchMetadata(await runtime.acquireClient(), sessionId);
    },

    async exists(sessionId) {
      requireNonBlank(sessionId);
      return (await fetchMetadata(await runtime.acquireClient(), sessionId)) !== undefined;
    },

    async getMessages(sessionId) {
      requireNonBlank(sessionId);
      const client = await runtime.acquireClient();
      if ((await fetchMetadata(client, sessionId)) === undefined) return undefined;
      const read = client.rpc?.sessions?.readPersistedEvents;
      if (!read) {
        throw new CopilotIntegrationError("The Copilot client cannot read persisted session events (sessions.readPersistedEvents)");
      }
      const messages: CopilotSessionMessage[] = [];
      let cursor: string | undefined;
      for (;;) {
        const page = await read.call(client.rpc!.sessions, {
          sessionId,
          max: PERSISTED_EVENTS_PAGE_SIZE,
          ...(cursor === undefined ? {} : { cursor }),
        });
        if (page.cursorStatus === "expired") {
          throw new CopilotIntegrationError(`The persisted history of session ${sessionId} changed while it was being read`);
        }
        for (const event of page.events) {
          const message = toMessage(event);
          if (message) messages.push(message);
        }
        if (!page.hasMore) return messages;
        cursor = page.cursor;
      }
    },

    async delete(sessionId) {
      requireNonBlank(sessionId);
      const release = await runtime.locks.acquire(sessionId, { waitMs: runtime.conversationWaitMs });
      try {
        const result = await withLease(sessionId, runtime.conversationWaitMs, async () => {
          const client = await runtime.acquireClient();
          if ((await fetchMetadata(client, sessionId)) === undefined) return false;
          await deleteSession(client, sessionId);
          return true;
        });
        if (result === BUSY) throw new CopilotConversationBusyError(sessionId);
        return result;
      } finally {
        release();
      }
    },

    sessionId(agent, conversationId, options = {}) {
      const name = typeof agent === "string" ? agent : agent?.name;
      return deriveSessionId(name, options.principal ?? getCopilotContext()?.principal, conversationId);
    },

    async deleteExpired(maxAgeMs) {
      if (typeof maxAgeMs !== "number" || Number.isNaN(maxAgeMs) || maxAgeMs <= 0) {
        throw new CopilotConfigurationError("maxAgeMs must be a positive number of milliseconds");
      }
      const client = runtime.runningClient();
      if (!client) return 0;
      const cutoff = Date.now() - maxAgeMs;
      const expired = (current: CopilotSessionMetadata | undefined): current is CopilotSessionMetadata =>
        current !== undefined && !current.isRemote && current.modifiedTime.getTime() < cutoff;
      let deleted = 0;
      for (const session of await listAll(client)) {
        if (!session.sessionId.startsWith(COPILOT_SESSION_ID_PREFIX) || !expired(session)) continue;
        const release = runtime.locks.tryAcquire(session.sessionId);
        if (!release) continue;
        try {
          const result = await withLease(session.sessionId, 0, async () => {
            if (!expired(await fetchMetadata(client, session.sessionId))) return false;
            await deleteSession(client, session.sessionId);
            return true;
          });
          if (result === true) deleted++;
        } catch (error) {
          runtime.logger.warn("Could not delete expired Copilot session", { sessionId: session.sessionId, error: messageOf(error) });
        } finally {
          release();
        }
      }
      return deleted;
    },
  };
}

const BUSY = Symbol("busy");

async function listAll(client: ClientLike): Promise<CopilotSessionMetadata[]> {
  if (!client.listSessions) throw new CopilotIntegrationError("The Copilot client cannot list sessions (listSessions)");
  const sessions = await client.listSessions();
  return sessions.map(toMetadata).filter((s): s is CopilotSessionMetadata => s !== undefined);
}

/** Normalizes SDK metadata (dates may arrive as strings over some transports). */
function toMetadata(value: unknown): CopilotSessionMetadata | undefined {
  if (!value || typeof value !== "object") return undefined;
  const raw = value as Record<string, unknown>;
  if (typeof raw.sessionId !== "string") return undefined;
  const metadata: CopilotSessionMetadata = {
    sessionId: raw.sessionId,
    startTime: toDate(raw.startTime),
    modifiedTime: toDate(raw.modifiedTime ?? raw.startTime),
    isRemote: raw.isRemote === true,
  };
  if (typeof raw.summary === "string") metadata.summary = raw.summary;
  if (raw.context !== undefined) metadata.context = raw.context;
  return metadata;
}

function toDate(value: unknown): Date {
  if (value instanceof Date) return value;
  if (typeof value === "string" || typeof value === "number") {
    const date = new Date(value);
    if (!Number.isNaN(date.getTime())) return date;
  }
  return new Date(0);
}

function toMessage(event: SessionEvent): CopilotSessionMessage | undefined {
  if (event.type !== "user.message" && event.type !== "assistant.message") return undefined;
  if (event.agentId) return undefined;
  const data = event.data as { content?: unknown; messageId?: unknown; parentToolCallId?: unknown };
  if (data.parentToolCallId) return undefined;
  const content = typeof data.content === "string" ? data.content : "";
  if (event.type === "assistant.message" && content === "") return undefined;
  const message: CopilotSessionMessage = {
    role: event.type === "user.message" ? "user" : "assistant",
    content,
    id: event.id,
    event,
  };
  if (typeof data.messageId === "string") message.messageId = data.messageId;
  const timestamp = event.timestamp ? new Date(event.timestamp) : undefined;
  if (timestamp && !Number.isNaN(timestamp.getTime())) message.timestamp = timestamp;
  return message;
}

function requireNonBlank(sessionId: unknown): asserts sessionId is string {
  if (typeof sessionId !== "string" || sessionId.trim() === "") throw new TypeError("sessionId must be a non-blank string");
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
