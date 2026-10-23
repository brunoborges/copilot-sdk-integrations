/**
 * Shared contracts for @copilot-sdk-integrations/agent-core.
 *
 * Every module in core, and the framework packages built on it, depend on these
 * types. Change them deliberately.
 */
import type {
  CopilotClientOptions,
  CustomAgentConfig,
  MCPHTTPServerConfig,
  MCPServerConfig,
  MCPStdioServerConfig,
  PermissionRequest,
  PermissionRequestResult,
  ProviderConfig,
  ResumeSessionConfig,
  SessionConfig,
  SessionEvent,
  SessionHooks,
  SystemMessageConfig,
  Tool as SdkTool,
  MessageSource,
  ToolSearchConfig,
} from "@github/copilot-sdk";

// ---------------------------------------------------------------------------
// Runtime abstraction (implemented by the real SDK client and the test runtime)
// ---------------------------------------------------------------------------

/** The subset of `CopilotSession` the core depends on. */
export interface SessionLike {
  readonly sessionId: string;
  send(options: { prompt: string; mode?: "enqueue" | "immediate"; source?: MessageSource }): Promise<string>;
  sendAndWait(options: { prompt: string; source?: MessageSource }, responseSchema: StructuredSchema<unknown>, timeout?: number): Promise<unknown>;
  on(handler: (event: SessionEvent) => void): () => void;
  abort(): Promise<void>;
  disconnect(): Promise<void>;
  getEvents(): Promise<SessionEvent[]>;
}

/** The subset of `CopilotClient` the core depends on. */
export interface ClientLike {
  start(): Promise<void>;
  stop(): Promise<Error[]>;
  createSession(config: SessionConfig): Promise<SessionLike>;
  resumeSession(sessionId: string, config: ResumeSessionConfig): Promise<SessionLike>;
  deleteSession(sessionId: string): Promise<void>;
  getSessionMetadata?(sessionId: string): Promise<unknown | undefined>;
  /** Lists persisted sessions (SDK `SessionMetadata`). Required by `copilot.sessions.list()` and the session cleanup. */
  listSessions?(): Promise<unknown[]>;
  /**
   * Server RPC surface. `copilot.sessions.getMessages()` uses `rpc.sessions.readPersistedEvents` to
   * read a session's history without resuming it.
   */
  readonly rpc?: {
    readonly sessions?: {
      readPersistedEvents?(params: { sessionId: string; cursor?: string; max?: number }): Promise<PersistedEventsPage>;
    };
  };
  listModels?(): Promise<Array<{ id: string; name?: string }>>;
  getStatus?(): Promise<{ version?: string } & Record<string, unknown>>;
  /**
   * Whether the connection to the runtime is alive. Optional: for the SDK's `CopilotClient` (which
   * has no public state accessor) core treats a throwing `rpc` accessor as a lost connection.
   */
  isConnected?(): boolean;
}

/** One page of `sessions.readPersistedEvents`. */
export interface PersistedEventsPage {
  events: SessionEvent[];
  cursor: string;
  hasMore: boolean;
  cursorStatus?: string;
}

/** Creates the underlying client. Default: `new CopilotClient(options)` from the SDK. */
export type ClientFactory = (options: CopilotClientOptions) => ClientLike;

// ---------------------------------------------------------------------------
// Schemas
// ---------------------------------------------------------------------------

/** Zod-compatible schema (Zod 4 satisfies this). Used for tool inputs and structured results. */
export interface StructuredSchema<T> {
  parse(value: unknown): T;
  /** Optional: Zod 4 instances expose JSON Schema through `z.toJSONSchema(schema)`; core handles conversion. */
  toJSONSchema?(): Record<string, unknown>;
}

// ---------------------------------------------------------------------------
// Request context
// ---------------------------------------------------------------------------

/** Per-turn context, propagated with AsyncLocalStorage into tools, policies, hooks, and listeners. */
export interface CopilotContext {
  /** Authenticated caller. Part of the session-id derivation. `undefined` for anonymous/system calls. */
  principal?: string;
  /**
   * The turn's conversation id (set by core while a turn runs). Outside a turn, a value set by the
   * application (for example by the NestJS context interceptor) is informational only: turns without
   * an explicit `conversationId` use an ephemeral session.
   */
  conversationId?: string;
  agentName?: string;
  /** SDK session id, set once the session is created or resumed. */
  sessionId?: string;
  /** Aborted when the turn is cancelled. */
  signal?: AbortSignal;
  /** Per-user GitHub token for this turn, if any. */
  githubToken?: string;
  /** Free-form attributes (request headers, tenant, trace ids, ...). */
  attributes: Record<string, unknown>;
}

// ---------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------

/** Native core tool shape. */
export interface CopilotToolDefinition<TInput = unknown> {
  name: string;
  description: string;
  /** Zod schema (preferred) or a raw JSON Schema object. */
  input?: StructuredSchema<TInput> | Record<string, unknown>;
  execute(input: TInput, invocation: ToolCallInfo): unknown | Promise<unknown>;
  skipPermission?: boolean;
  overridesBuiltInTool?: boolean;
}

export interface ToolCallInfo {
  toolCallId: string;
  toolName: string;
  sessionId: string;
  signal?: AbortSignal;
  context: CopilotContext;
}

/**
 * Anything accepted in `tools`:
 * - a `CopilotToolDefinition`
 * - an SDK `Tool` (from `defineTool`)
 * - a record of AI SDK `tool()` objects keyed by tool name (`{ name: tool({...}) }`)
 */
export type ToolInput =
  | CopilotToolDefinition<any>
  | SdkTool<any>
  | Record<string, AiSdkToolLike>;

/** Structural shape of an AI SDK `tool()` object (no dependency on `ai`). */
export interface AiSdkToolLike {
  /** AI SDK 7 also accepts a function of the tool context. */
  description?: string | ((options: { context: any; experimental_sandbox?: any }) => string);
  inputSchema?: unknown;
  // Method syntax keeps parameters bivariant so AI SDK tools (stricter option types) are assignable.
  execute?(
    input: any,
    options: { toolCallId: string; abortSignal?: AbortSignal; messages: unknown[]; context?: unknown },
  ): unknown;
  needsApproval?:
    | boolean
    | {
        bivarianceHack(input: any, options: { toolCallId: string; messages: unknown[]; context?: unknown }): boolean | PromiseLike<boolean>;
      }["bivarianceHack"];
}

// ---------------------------------------------------------------------------
// Governance
// ---------------------------------------------------------------------------

export type PermissionDecision = "approve" | "deny" | "ask-user" | "abstain";

/**
 * A permission policy. Returns:
 * - "approve" / "deny": final;
 * - "ask-user": route to a human (streaming calls), otherwise treated as deny;
 * - "abstain": this policy has no opinion (used for composition; final fallback is deny).
 */
export type PermissionPolicy = (
  request: PermissionRequest,
  context: PermissionPolicyContext,
) => PermissionDecision | Promise<PermissionDecision>;

export interface PermissionPolicyContext {
  agent: CopilotAgentDefinition;
  /** Names of the agent's own (custom) tools. */
  customToolNames: ReadonlySet<string>;
  context: CopilotContext;
}

/** Decision a human returns for an "ask-user" permission. */
export interface PermissionResponse {
  approved: boolean;
  feedback?: string;
}

export type { PermissionRequest, PermissionRequestResult };

// ---------------------------------------------------------------------------
// Agents
// ---------------------------------------------------------------------------

/**
 * String map (MCP `headers` or `env`) whose entries can be derived from the turn's `CopilotContext`,
 * resolved each time a session is created or resumed. Either a function returning the whole map,
 * or a map whose values are strings or functions of the context. `undefined` values are dropped.
 */
export type ContextualStringMap =
  | Record<string, string | undefined | ((context: CopilotContext) => string | undefined | Promise<string | undefined>)>
  | ((context: CopilotContext) => Record<string, string | undefined> | Promise<Record<string, string | undefined>>);

/** A local (stdio) MCP server whose `env` may depend on the request context. */
export type CopilotMCPStdioServerConfig = Omit<MCPStdioServerConfig, "env"> & { env?: ContextualStringMap };
/** A remote (HTTP/SSE) MCP server whose `headers` may depend on the request context (e.g. a per-user token). */
export type CopilotMCPHTTPServerConfig = Omit<MCPHTTPServerConfig, "headers"> & { headers?: ContextualStringMap };
/** An SDK `MCPServerConfig`, or one with context-derived `headers`/`env`. */
export type CopilotMCPServerConfig = MCPServerConfig | CopilotMCPStdioServerConfig | CopilotMCPHTTPServerConfig;

export type Instructions =
  | string
  | { mode: "append" | "replace"; content: string }
  | SystemMessageConfig;

export interface CopilotAgentDefinition {
  /** Stable name. Part of the session identity; must match /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$/. */
  name: string;
  description?: string;
  instructions?: Instructions;
  model?: string;
  reasoningEffort?: "low" | "medium" | "high" | "xhigh";
  /** BYOK provider. */
  provider?: ProviderConfig;
  tools?: ToolInput[];
  /** MCP servers keyed by name. `headers` (remote) and `env` (local) may be functions of the `CopilotContext`. */
  mcpServers?: Record<string, CopilotMCPServerConfig>;
  /** Allow-list in SDK ToolSet syntax (`builtin:*`, `custom:*`, `mcp:server`, exact names). Mutually exclusive with `excludedTools`. */
  availableTools?: string[];
  excludedTools?: string[];
  /** When false, no built-in runtime tools are available. Default: false. */
  builtInTools?: boolean;
  /**
   * Tool search: defers MCP/external tool definitions behind the built-in `tool_search_tool` once
   * the tool count reaches `deferThreshold`. Unset: runtime default (enabled, threshold 30).
   */
  toolSearch?: ToolSearchConfig;
  /** Default: `permissions.denyAll()`. */
  permissions?: PermissionPolicy;
  hooks?: SessionHooks;
  customAgents?: CustomAgentConfig[];
  infiniteSessions?: boolean;
  /**
   * Provenance label sent with each prompt: `"user"`, `"system"` (an automated caller), or
   * `"agent-<id>"`. Omitted: the runtime default (`user`). Caller-supplied provenance, not an
   * authenticated identity. A `withMessageSource` scope and calls from another agent's tool take precedence.
   */
  messageSource?: MessageSource;
  /** Escape hatch: adjust the final SDK session config. */
  configureSession?: (config: SessionConfig, context: CopilotContext) => SessionConfig;
}

// ---------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------

export type CopilotAgentEvent =
  | { type: "turn-start"; sessionId: string }
  | { type: "text-delta"; delta: string; messageId?: string }
  | { type: "reasoning-delta"; delta: string }
  | { type: "message"; content: string; messageId?: string }
  | { type: "tool-start"; toolCallId: string; toolName: string; input: unknown }
  | { type: "tool-end"; toolCallId: string; toolName: string; success: boolean; output?: unknown; error?: string }
  | {
      type: "permission-request";
      requestId: string;
      request: PermissionRequest;
      /** Resolve an "ask-user" permission from the stream consumer. */
      respond(response: PermissionResponse): void;
    }
  | { type: "permission-resolved"; requestId: string; approved: boolean }
  | { type: "usage"; model?: string; inputTokens?: number; outputTokens?: number }
  | { type: "error"; error: Error }
  | { type: "done"; text: string; sessionId: string }
  /** Raw SDK event, for anything not mapped above. */
  | { type: "raw"; event: SessionEvent };

// ---------------------------------------------------------------------------
// Session stores
// ---------------------------------------------------------------------------

/** Cross-replica coordination. `memoryStore()` is single-process. */
export interface SessionStore {
  /** Acquire an exclusive lease. Resolves to a release function, or `undefined` if not acquired within `waitMs`. */
  acquireLease(key: string, ttlMs: number, waitMs: number): Promise<(() => Promise<void>) | undefined>;
  get(key: string): Promise<string | undefined>;
  set(key: string, value: string, ttlMs?: number): Promise<void>;
  delete(key: string): Promise<void>;
}

// ---------------------------------------------------------------------------
// Client options and API
// ---------------------------------------------------------------------------

export interface CopilotLimits {
  /** Max concurrent turns across all agents. Default 16. */
  maxConcurrentTurns?: number;
  /** Max turns waiting for a slot. Default 64. Beyond that, `CopilotAdmissionRejectedError`. */
  maxQueuedTurns?: number;
  /** Max wait for a busy conversation. Default 30_000 ms. */
  conversationWaitMs?: number;
}

/**
 * Runtime recovery. When a start fails, or a running runtime's process dies or its connection
 * drops, the client becomes `failed`, in-flight turns fail with `CopilotRuntimeUnavailableError`,
 * and the next turn (or `start()`) starts a new runtime. The first restart is immediate; after
 * consecutive failed starts, the next one waits `backoffMs`, doubling up to `maxBackoffMs`.
 */
export interface CopilotRestartOptions {
  /**
   * Consecutive failed start attempts after which turns fail fast without trying again, until
   * `start()` is called explicitly. `0` disables automatic restarts. Default: `Infinity`.
   */
  maxAttempts?: number;
  /** Delay before the second consecutive start attempt, doubled for each later one. Default 1_000 ms. */
  backoffMs?: number;
  /** Upper bound of the delay between start attempts. Default 30_000 ms. */
  maxBackoffMs?: number;
  /** How often a running runtime's connection is checked. Default 1_000 ms. */
  checkIntervalMs?: number;
}

export interface CopilotOptions {
  /** Path to the Copilot CLI/runtime. Falls back to `COPILOT_CLI_PATH`, then the SDK's bundled runtime. */
  cliPath?: string;
  /** Connect to an existing runtime server instead of spawning one. */
  cliUrl?: string;
  /**
   * "lazy" (default): the runtime starts on the first turn. "eager": core itself does nothing
   * different; call `await copilot.start()` at application startup so a broken runtime fails fast.
   * Framework modules act on it (NestJS calls `start()` at bootstrap and fails bootstrap on error).
   */
  startup?: "lazy" | "eager";
  /** Service GitHub token (fallback when the context has none). */
  githubToken?: string;
  /** Use the logged-in CLI user. Default: true when no token is configured. */
  useLoggedInUser?: boolean;
  /** COPILOT_HOME for the runtime. */
  baseDirectory?: string;
  logLevel?: "none" | "error" | "warning" | "info" | "debug" | "all";
  limits?: CopilotLimits;
  /** Default per-turn timeout in ms. Default 300_000. */
  responseTimeout?: number;
  /** Max time to wait for an "ask-user" permission response. Default 120_000 ms. */
  approvalWindow?: number;
  /** Recovery when the runtime fails to start or its process dies or disconnects. */
  restart?: CopilotRestartOptions;
  store?: SessionStore;
  /** Global hooks applied before agent hooks. */
  hooks?: SessionHooks;
  /** Global event listener. Failures are logged, never propagated. */
  onEvent?: (event: CopilotAgentEvent, context: CopilotContext) => void;
  /** Resolves a per-user GitHub token for the current context. */
  tokenResolver?: (context: CopilotContext) => string | undefined | Promise<string | undefined>;
  /** Adjust the raw SDK client options. */
  configureClient?: (options: CopilotClientOptions) => CopilotClientOptions;
  /** Replace the SDK client (tests use the scripted runtime). */
  clientFactory?: ClientFactory;
  logger?: CopilotLogger;
}

export interface CopilotLogger {
  debug(message: string, meta?: Record<string, unknown>): void;
  info(message: string, meta?: Record<string, unknown>): void;
  warn(message: string, meta?: Record<string, unknown>): void;
  error(message: string, meta?: Record<string, unknown>): void;
}

export interface SendOptions<T = string> {
  prompt: string;
  /** Persistent conversation key. Omit for an ephemeral session. */
  conversationId?: string;
  /** Structured result schema. */
  schema?: StructuredSchema<T>;
  signal?: AbortSignal;
  /** Overrides `responseTimeout`. */
  timeout?: number;
  /** Extra tools for this call. */
  tools?: ToolInput[];
  /** Merged into the AsyncLocalStorage context for this turn. */
  context?: Partial<CopilotContext>;
  /** Resolve "ask-user" permissions in non-streaming calls. Default: deny. */
  onPermissionRequest?: (request: PermissionRequest) => PermissionResponse | Promise<PermissionResponse>;
}

export type ConversationMode = "create-or-resume" | "resume-only" | "create-only";

export interface AgentHandle {
  readonly definition: CopilotAgentDefinition;
  /** Run a turn and return the final text. */
  send(options: Omit<SendOptions<string>, "schema"> & { schema?: undefined }): Promise<string>;
  /** Run a turn and return a validated structured result. */
  send<T>(options: SendOptions<T> & { schema: StructuredSchema<T> }): Promise<T>;
  /** Run a turn and stream events. Breaking out of the iteration aborts the turn. */
  stream(options: Omit<SendOptions, "schema">): AsyncIterable<CopilotAgentEvent>;
  /** A conversation-bound handle (resume-only by default). */
  conversation(conversationId: string, options?: { mode?: ConversationMode; principal?: string }): BoundConversation;
  /** Delete the conversation's session. */
  deleteConversation(conversationId: string, options?: { principal?: string }): Promise<void>;
}

export interface BoundConversation {
  readonly conversationId: string;
  send(prompt: string, options?: Omit<SendOptions<string>, "prompt" | "conversationId" | "schema">): Promise<string>;
  send<T>(prompt: string, options: Omit<SendOptions<T>, "prompt" | "conversationId"> & { schema: StructuredSchema<T> }): Promise<T>;
  stream(prompt: string, options?: Omit<SendOptions, "prompt" | "conversationId" | "schema">): AsyncIterable<CopilotAgentEvent>;
}

/** Metadata of a persisted session (normalized from the SDK's `SessionMetadata`). */
export interface CopilotSessionMetadata {
  sessionId: string;
  startTime: Date;
  modifiedTime: Date;
  summary?: string;
  /** Remote (cloud) sessions are never listed or swept. */
  isRemote: boolean;
  /** Working-directory context recorded at session creation, as reported by the SDK. */
  context?: unknown;
}

/** A user or assistant message of a session's history. */
export interface CopilotSessionMessage {
  role: "user" | "assistant";
  content: string;
  /** The SDK event id. */
  id: string;
  messageId?: string;
  timestamp?: Date;
  /** The raw SDK event. */
  event: SessionEvent;
}

/**
 * Lists, reads, and deletes persisted Copilot sessions (`copilot.sessions`). Every method except
 * `sessionId()` and `deleteExpired()` starts a lazy runtime.
 *
 * With the SDK-managed runtime, sessions live in its `baseDirectory` (COPILOT_HOME), which may also
 * hold sessions created by other tools; with `cliUrl`, every session of the shared runtime is visible.
 */
export interface CopilotSessions {
  /** Local (non-remote) sessions, most recently modified first. */
  list(): Promise<CopilotSessionMetadata[]>;
  /** A session's metadata, or `undefined` when it does not exist. */
  get(sessionId: string): Promise<CopilotSessionMetadata | undefined>;
  exists(sessionId: string): Promise<boolean>;
  /**
   * The session's user and assistant messages in order, read from its persisted history without
   * resuming it. Sub-agent events, tool calls, and empty assistant messages are omitted.
   * `undefined` when the session does not exist.
   */
  getMessages(sessionId: string): Promise<CopilotSessionMessage[] | undefined>;
  /**
   * Deletes a session and its history; waits (up to `limits.conversationWaitMs`) for a turn in flight
   * on it to finish first. Returns `false` when the session did not exist.
   */
  delete(sessionId: string): Promise<boolean>;
  /**
   * The SDK session id of an agent's conversation (see `deriveSessionId`). `principal` defaults to the
   * ambient context's principal, like `agent.deleteConversation()`.
   */
  sessionId(agent: string | CopilotAgentDefinition, conversationId: string, options?: { principal?: string }): string;
  /**
   * Deletes local sessions created by this library (ids starting with `COPILOT_SESSION_ID_PREFIX`)
   * that were not modified within `maxAgeMs`. Skips sessions with a turn in flight and re-checks each
   * session's metadata right before deleting it. Never starts the runtime: returns 0 while it isn't
   * running. Per-session failures are logged and skipped. Returns the number of sessions deleted.
   */
  deleteExpired(maxAgeMs: number): Promise<number>;
}

export type CopilotClientState = "not-started" | "starting" | "running" | "stopping" | "stopped" | "failed";

export interface CopilotCapacity {
  maxConcurrentTurns: number;
  activeTurns: number;
  maxQueuedTurns: number;
  queuedTurns: number;
}

export interface Copilot {
  readonly state: CopilotClientState;
  /**
   * Starts the runtime now (otherwise the first turn starts it). On a `failed` client it retries
   * immediately and resets the restart attempts and backoff.
   */
  start(): Promise<void>;
  /** Stop admitting turns, wait up to `gracePeriodMs` for in-flight turns, abort the rest, stop the runtime. */
  stop(options?: { gracePeriodMs?: number }): Promise<void>;
  /** The underlying client (starts it if lazy). */
  client(): Promise<ClientLike>;
  agent(definition: CopilotAgentDefinition): AgentHandle;
  capacity(): CopilotCapacity;
  /** Lists, reads, and deletes persisted sessions. */
  readonly sessions: CopilotSessions;
  /** Runtime info without starting a lazy client. */
  health(): Promise<{ state: CopilotClientState; version?: string; capacity: CopilotCapacity }>;
}
