/**
 * Minimal in-memory `ClientLike`/`SessionLike` fakes for the core runtime tests. They mimic the
 * SDK's observable behavior: sessions persist until deleted, resume of an unknown id fails with
 * "Session not found", `session.on` only receives live events, and a turn ends with `session.idle`.
 */
import type { PermissionRequest, PermissionRequestResult, ResumeSessionConfig, SessionConfig, SessionEvent } from "@github/copilot-sdk";
import { vi } from "vitest";
import type { ClientLike, SessionLike, StructuredSchema } from "../src/types.js";

let counter = 0;

export function sdkEvent(type: string, data: Record<string, unknown>, extra: Record<string, unknown> = {}): SessionEvent {
  return {
    id: `evt-${++counter}`,
    timestamp: new Date().toISOString(),
    parentId: null,
    type,
    data,
    ...extra,
  } as unknown as SessionEvent;
}

export const tick = () => new Promise<void>((resolve) => setImmediate(resolve));

export function deferred<T = void>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** Behavior of one turn. Default: echo the prompt. */
export type TurnScript = (session: FakeSession, prompt: string) => Promise<void> | void;

export const echoScript: TurnScript = async (session, prompt) => {
  await tick();
  session.emit("user.message", { content: prompt });
  session.emit("assistant.message_delta", { messageId: "m1", deltaContent: "echo: " });
  session.emit("assistant.message_delta", { messageId: "m1", deltaContent: prompt });
  session.emit("assistant.message", { messageId: "m1", content: `echo: ${prompt}` });
  session.emit("assistant.usage", { model: "fake-model", inputTokens: 3, outputTokens: 5 });
  session.emit("session.idle", {});
};

/** Replies with `content` (e.g. JSON for structured output). */
export function replyScript(content: string): TurnScript {
  return async (session, prompt) => {
    await tick();
    session.emit("user.message", { content: prompt });
    session.emit("assistant.message", { messageId: "m1", content });
    session.emit("session.idle", {});
  };
}

/** Never completes until aborted (then emits an aborted idle). */
export const hangingScript: TurnScript = async (session, prompt) => {
  await tick();
  session.emit("user.message", { content: prompt });
};

export class FakeSession implements SessionLike {
  readonly listeners = new Set<(event: SessionEvent) => void>();
  readonly prompts: string[] = [];
  readonly abort = vi.fn(async () => {
    this.aborted = true;
    this.emit("session.idle", { aborted: true });
  });
  readonly disconnect = vi.fn(async () => {
    this.disconnected = true;
  });
  aborted = false;
  disconnected = false;

  constructor(
    readonly sessionId: string,
    readonly config: SessionConfig | ResumeSessionConfig,
    readonly client: FakeClient,
    readonly resumed: boolean,
  ) {}

  emit(type: string, data: Record<string, unknown>, extra?: Record<string, unknown>): void {
    const event = sdkEvent(type, data, extra);
    for (const listener of [...this.listeners]) listener(event);
  }

  /** Simulates the runtime asking for a permission (as the SDK's JSON-RPC handler would). */
  async requestPermission(request: PermissionRequest): Promise<PermissionRequestResult> {
    return (await this.config.onPermissionRequest!(request, { sessionId: this.sessionId })) as PermissionRequestResult;
  }

  on(handler: (event: SessionEvent) => void): () => void {
    this.listeners.add(handler);
    return () => this.listeners.delete(handler);
  }

  async send(options: { prompt: string }): Promise<string> {
    this.prompts.push(options.prompt);
    this.client.sends.push({ sessionId: this.sessionId, prompt: options.prompt });
    const script = this.client.script;
    void Promise.resolve()
      .then(() => script(this, options.prompt))
      .catch((error: unknown) => this.emit("session.error", { errorType: "script", message: String(error) }));
    return `msg-${this.prompts.length}`;
  }

  async sendAndWait(options: { prompt: string }, schema: StructuredSchema<unknown>, _timeout?: number): Promise<unknown> {
    this.client.responseSchemas.push(schema.toJSONSchema?.());
    let last: string | undefined;
    const done = new Promise<void>((resolve, reject) => {
      const off = this.on((event) => {
        if (event.type === "assistant.message") last = event.data.content;
        if (event.type === "session.idle") {
          off();
          resolve();
        }
        if (event.type === "session.error") {
          off();
          reject(new Error(event.data.message));
        }
      });
    });
    await this.send(options);
    await done;
    if (last === undefined) throw new Error("Session became idle without a structured assistant response");
    return schema.parse(JSON.parse(last));
  }

  async getEvents(): Promise<SessionEvent[]> {
    return [];
  }
}

export interface FakeClientOptions {
  script?: TurnScript;
  /** Expose `getSessionMetadata` (default true). */
  metadata?: boolean;
  start?: () => Promise<void>;
}

export class FakeClient implements ClientLike {
  script: TurnScript;
  readonly persisted = new Map<string, SessionConfig>();
  readonly sessions: FakeSession[] = [];
  readonly sends: Array<{ sessionId: string; prompt: string }> = [];
  readonly responseSchemas: unknown[] = [];
  readonly created: SessionConfig[] = [];
  readonly resumedConfigs: Array<{ sessionId: string; config: ResumeSessionConfig }> = [];
  readonly start = vi.fn(async () => {
    await this.startImpl?.();
  });
  readonly stop = vi.fn(async (): Promise<Error[]> => []);
  readonly deleteSession = vi.fn(async (sessionId: string) => {
    this.persisted.delete(sessionId);
  });
  getSessionMetadata?: (sessionId: string) => Promise<unknown | undefined>;
  getStatus = vi.fn(async () => ({ version: "9.9.9" }));
  private readonly startImpl?: () => Promise<void>;

  constructor(options: FakeClientOptions = {}) {
    this.script = options.script ?? echoScript;
    this.startImpl = options.start;
    if (options.metadata !== false) {
      this.getSessionMetadata = vi.fn(async (sessionId: string) =>
        this.persisted.has(sessionId) ? { sessionId } : undefined,
      );
    }
  }

  async createSession(config: SessionConfig): Promise<SessionLike> {
    const id = config.sessionId!;
    if (this.persisted.has(id)) throw new Error(`Session ${id} already exists`);
    this.persisted.set(id, config);
    this.created.push(config);
    const session = new FakeSession(id, config, this, false);
    this.sessions.push(session);
    return session;
  }

  async resumeSession(sessionId: string, config: ResumeSessionConfig): Promise<SessionLike> {
    if (!this.persisted.has(sessionId)) throw new Error(`Session not found: ${sessionId}`);
    this.resumedConfigs.push({ sessionId, config });
    const session = new FakeSession(sessionId, config, this, true);
    this.sessions.push(session);
    return session;
  }

  get lastSession(): FakeSession {
    return this.sessions[this.sessions.length - 1]!;
  }
}

export const silentLogger = () => ({
  debug: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
});
