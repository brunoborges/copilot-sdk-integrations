import type {
  AgentHandle,
  Copilot,
  CopilotAgentDefinition,
  CopilotAgentEvent,
  CopilotContext,
  CopilotOptions,
  CopilotSessionCleanupOptions,
  CopilotSessions,
  SessionCleanup,
  ToSseStreamOptions,
} from "@copilot-sdk-integrations/agent-core";
import type { FastifyReply, FastifyRequest, onRequestHookHandler } from "fastify";

/**
 * Agent names known to the app, for typed `agent(name)` lookups. Empty by default (any string is
 * accepted); apps augment it:
 *
 * ```ts
 * declare module "@copilot-sdk-integrations/fastify" {
 *   interface CopilotAgentRegistry {
 *     support: typeof support;
 *   }
 * }
 * ```
 */
// eslint-disable-next-line @typescript-eslint/no-empty-interface
export interface CopilotAgentRegistry {}

/** `keyof CopilotAgentRegistry` when the registry is augmented, otherwise `string`. */
export type CopilotAgentName = [keyof CopilotAgentRegistry] extends [never] ? string : Extract<keyof CopilotAgentRegistry, string>;

/** `fastify.copilot`: the core `Copilot` plus agents registered by name. */
export interface FastifyCopilot extends Omit<Copilot, "agent"> {
  /** The handle of a registered agent. Unknown names throw a `CopilotConfigurationError` listing the registered agents. */
  agent(name: CopilotAgentName): AgentHandle;
  /** The handle of any agent definition (same as the core `copilot.agent(definition)`). */
  agent(definition: CopilotAgentDefinition): AgentHandle;
  /** Registered agent definitions (with `agentOverrides` applied), in registration order. */
  agents(): CopilotAgentDefinition[];
  /** Registers an agent. Throws after `ready()` or when the name is already registered. */
  registerAgent(definition: CopilotAgentDefinition): void;
  /** The underlying core `Copilot`. */
  readonly core: Copilot;
}

/** `request.copilot`: agent handles bound to the request's context and abort signal. */
export interface RequestCopilot {
  /**
   * The handle of a registered agent (or of a definition) whose `send`, `stream`, and
   * `conversation` calls default `context` to `request.copilot.context` and `signal` to
   * `request.copilot.signal`. Explicit arguments win.
   */
  agent(name: CopilotAgentName): AgentHandle;
  agent(definition: CopilotAgentDefinition): AgentHandle;
  /** The request's Copilot context (principal, attributes). Empty before the context hook ran or when it is disabled. */
  readonly context: CopilotContext;
  /** Aborted when the client disconnects before the response finishes. `undefined` when the context hook is disabled. */
  readonly signal: AbortSignal | undefined;
}

export interface CopilotContextOptions {
  /** Principal for session-id derivation and permission ownership. Default: `request.user?.id ?? request.user?.sub`. */
  principal?: (request: FastifyRequest) => string | undefined | Promise<string | undefined>;
  /** Request headers copied into `context.attributes.headers` (for MCP header functions, token resolvers). */
  headers?: string[];
  /** Extra attributes for tools and policies. */
  attributes?: (request: FastifyRequest) => Record<string, unknown> | Promise<Record<string, unknown>>;
  /** Abort turns started through `request.copilot` when the client disconnects. Default true. */
  abortOnDisconnect?: boolean;
  /** Hook stage that captures the context. Default `"preHandler"`, which runs after auth in `onRequest`/`preValidation`. */
  hook?: "onRequest" | "preValidation" | "preHandler";
}

export interface CopilotHealthRouteOptions {
  /** Route path. Default `"/health/copilot"`. */
  path?: string;
}

export interface CopilotInfoRouteOptions {
  /** Route path. Default `"/copilot/info"`. */
  path?: string;
  /** Guards for the route (strongly recommended outside development). */
  onRequest?: onRequestHookHandler | onRequestHookHandler[];
  /** Include each agent's instructions. Default false. */
  includeInstructions?: boolean;
}

export interface CopilotPluginOptions {
  /** Core client options (cliPath, cliUrl, githubToken, limits, restart, ...), or an existing `Copilot`. */
  client?: CopilotOptions | Copilot;
  /** `"lazy"` (default) starts the runtime on the first turn; `"eager"` starts it in `onReady`. */
  startup?: "lazy" | "eager";
  /** Agents available through `fastify.copilot.agent(name)` and `request.copilot.agent(name)`. */
  agents?: CopilotAgentDefinition[];
  /** Per-agent overrides by name (model, provider, ...), merged over the definitions. */
  agentOverrides?: Record<string, Partial<CopilotAgentDefinition>>;
  /** Request context capture; `false` disables the hook. */
  context?: CopilotContextOptions | false;
  /** Root error handler mapping Copilot errors to HTTP; `false` skips it. Default true. */
  errorHandler?: boolean;
  /** Health route; off unless set. `true` uses the default path. */
  health?: CopilotHealthRouteOptions | boolean;
  /** Info route; off unless set, and should be guarded with `onRequest`. `true` uses the default path. */
  info?: CopilotInfoRouteOptions | boolean;
  /** How long close waits for in-flight turns before aborting them. Default 30_000 ms. */
  shutdownGracePeriod?: number;
  /** Stop the client on close. Default: true when the plugin created it, false when one was passed in. */
  stopOnClose?: boolean;
  /**
   * Periodically delete sessions created by this library that were not modified within `maxAge` ms.
   * Disabled unless set. `interval` defaults to half of `maxAge`, clamped to between one minute and
   * one hour. Starts in `onReady`, stops in `onClose`; sweeps never start the runtime.
   */
  sessionCleanup?: CopilotSessionCleanupOptions;
}

export type CopilotEventsOptions = ToSseStreamOptions;

declare module "fastify" {
  interface FastifyInstance {
    copilot: FastifyCopilot;
    /** Lists, reads, and deletes persisted Copilot sessions (same as `fastify.copilot.sessions`). */
    copilotSessions: CopilotSessions;
    /** The session cleanup job (`sweep()` runs one now), or `null` when `sessionCleanup` is not set. */
    copilotSessionCleanup: SessionCleanup | null;
  }
  interface FastifyRequest {
    copilot: RequestCopilot;
  }
  interface FastifyReply {
    /**
     * Streams Copilot events as Server-Sent Events (`text/event-stream`). Waits for the first event,
     * so a turn that fails before streaming (for example admission or start errors) goes to the
     * error handler with a real HTTP status; later errors become an `event: error` frame.
     */
    copilotEvents(events: AsyncIterable<CopilotAgentEvent>, options?: CopilotEventsOptions): Promise<FastifyReply>;
  }
}

export type { FastifyReply, FastifyRequest };
