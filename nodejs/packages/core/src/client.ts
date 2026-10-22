import { homedir } from "node:os";
import { join } from "node:path";
import { CopilotClient, RuntimeConnection, type CopilotClientOptions } from "@github/copilot-sdk";
import { createAgentHandle } from "./agent.js";
import { AdmissionController, ConversationLocks } from "./conversation.js";
import {
  CopilotConfigurationError,
  CopilotIntegrationError,
  CopilotRuntimeUnavailableError,
  CopilotShuttingDownError,
} from "./errors.js";
import type {
  AgentHandle,
  ClientFactory,
  ClientLike,
  Copilot,
  CopilotAgentDefinition,
  CopilotCapacity,
  CopilotClientState,
  CopilotLogger,
  CopilotOptions,
} from "./types.js";

export const DEFAULT_MAX_CONCURRENT_TURNS = 16;
export const DEFAULT_MAX_QUEUED_TURNS = 64;
export const DEFAULT_CONVERSATION_WAIT_MS = 30_000;
export const DEFAULT_RESPONSE_TIMEOUT_MS = 300_000;
export const DEFAULT_APPROVAL_WINDOW_MS = 120_000;
export const DEFAULT_SHUTDOWN_GRACE_MS = 30_000;
/** After aborting the remaining turns on stop, how long to wait for them to release. */
const ABORT_SETTLE_MS = 5_000;

/** Internal services shared by the agent executors of one `Copilot` instance. */
export interface CopilotRuntime {
  readonly options: CopilotOptions;
  readonly logger: CopilotLogger;
  readonly admission: AdmissionController;
  readonly locks: ConversationLocks;
  readonly conversationWaitMs: number;
  readonly responseTimeout: number;
  readonly approvalWindow: number;
  /** The started client (lazy start, shared in-flight start). */
  acquireClient(): Promise<ClientLike>;
  /** Track an executing turn so `stop()` can abort it. Returns an unregister function. */
  registerTurn(controller: AbortController): () => void;
}

/** Default logger: `console`, prefixed with `[copilot]`. Debug messages are dropped. */
export function consoleLogger(): CopilotLogger {
  const fmt = (message: string) => `[copilot] ${message}`;
  const args = (meta?: Record<string, unknown>) => (meta === undefined ? [] : [meta]);
  return {
    debug: () => {},
    info: (m, meta) => console.info(fmt(m), ...args(meta)),
    warn: (m, meta) => console.warn(fmt(m), ...args(meta)),
    error: (m, meta) => console.error(fmt(m), ...args(meta)),
  };
}

/** The SDK's `CopilotClient` as a `ClientLike` (structurally compatible apart from overload typing). */
export const defaultClientFactory: ClientFactory = (options) => new CopilotClient(options) as unknown as ClientLike;

/** Translates `CopilotOptions` into SDK `CopilotClientOptions` (before `configureClient`). */
export function buildClientOptions(options: CopilotOptions, env: NodeJS.ProcessEnv = process.env): CopilotClientOptions {
  const result: CopilotClientOptions = { mode: "empty" };
  if (options.logLevel !== undefined) result.logLevel = options.logLevel;
  if (options.cliUrl !== undefined) {
    if (options.cliPath !== undefined) {
      throw new CopilotConfigurationError("cliUrl and cliPath are mutually exclusive");
    }
    if (options.useLoggedInUser !== undefined) {
      throw new CopilotConfigurationError(
        "useLoggedInUser cannot be combined with cliUrl: an external runtime manages its own authentication",
      );
    }
    result.connection = RuntimeConnection.forUri(options.cliUrl);
    // The SDK rejects client-level gitHubToken for external servers; the agent executor uses
    // `githubToken` as the per-session fallback instead.
    if (options.baseDirectory !== undefined) result.baseDirectory = options.baseDirectory;
  } else {
    const cliPath = options.cliPath ?? nonBlank(env.COPILOT_CLI_PATH);
    if (cliPath !== undefined) result.connection = RuntimeConnection.forStdio({ path: cliPath });
    if (options.githubToken !== undefined) result.gitHubToken = options.githubToken;
    if (options.useLoggedInUser !== undefined) result.useLoggedInUser = options.useLoggedInUser;
    // Empty mode requires an explicit persistence location; default to the runtime's own default.
    result.baseDirectory = options.baseDirectory ?? nonBlank(env.COPILOT_HOME) ?? join(homedir(), ".copilot");
  }
  return result;
}

function nonBlank(value: string | undefined): string | undefined {
  return value !== undefined && value.trim() !== "" ? value : undefined;
}

function positiveInt(name: string, value: number | undefined, fallback: number, allowZero = false): number {
  if (value === undefined) return fallback;
  if (!Number.isInteger(value) || value < (allowZero ? 0 : 1)) {
    throw new CopilotConfigurationError(`${name} must be ${allowZero ? "a non-negative" : "a positive"} integer`);
  }
  return value;
}

function positiveMs(name: string, value: number | undefined, fallback: number): number {
  if (value === undefined) return fallback;
  if (typeof value !== "number" || Number.isNaN(value) || value <= 0) {
    throw new CopilotConfigurationError(`${name} must be a positive number of milliseconds`);
  }
  return value;
}

/** Creates the Copilot runtime manager. Nothing starts until `start()`, `client()`, or the first turn. */
export function createCopilot(options: CopilotOptions = {}): Copilot {
  const logger = options.logger ?? consoleLogger();
  const limits = options.limits ?? {};
  const maxConcurrent = positiveInt("limits.maxConcurrentTurns", limits.maxConcurrentTurns, DEFAULT_MAX_CONCURRENT_TURNS);
  const maxQueued = positiveInt("limits.maxQueuedTurns", limits.maxQueuedTurns, DEFAULT_MAX_QUEUED_TURNS, true);
  const conversationWaitMs = positiveInt(
    "limits.conversationWaitMs",
    limits.conversationWaitMs,
    DEFAULT_CONVERSATION_WAIT_MS,
    true,
  );
  const responseTimeout = positiveMs("responseTimeout", options.responseTimeout, DEFAULT_RESPONSE_TIMEOUT_MS);
  const approvalWindow = positiveMs("approvalWindow", options.approvalWindow, DEFAULT_APPROVAL_WINDOW_MS);
  if (options.startup !== undefined && options.startup !== "lazy" && options.startup !== "eager") {
    throw new CopilotConfigurationError(`startup must be "lazy" or "eager"`);
  }
  if (options.cliUrl !== undefined && options.cliPath !== undefined) {
    throw new CopilotConfigurationError("cliUrl and cliPath are mutually exclusive");
  }
  const factory = options.clientFactory ?? defaultClientFactory;

  const admission = new AdmissionController(maxConcurrent, maxQueued);
  const locks = new ConversationLocks();
  const turns = new Set<AbortController>();

  let state: CopilotClientState = "not-started";
  let client: ClientLike | undefined;
  let startPromise: Promise<ClientLike> | undefined;
  let stopPromise: Promise<void> | undefined;

  const start = (): Promise<ClientLike> => {
    if (state === "stopping" || state === "stopped") return Promise.reject(new CopilotShuttingDownError());
    if (state === "running" && client) return Promise.resolve(client);
    if (startPromise) return startPromise;
    state = "starting";
    const attempt = (async () => {
      let candidate: ClientLike | undefined;
      try {
        let sdkOptions = buildClientOptions(options);
        if (options.configureClient) sdkOptions = options.configureClient(sdkOptions);
        candidate = factory(sdkOptions);
        await candidate.start();
      } catch (error) {
        if (state === "starting") state = "failed";
        if (candidate) {
          await candidate.stop().catch(() => undefined);
        }
        if (error instanceof CopilotConfigurationError) throw error;
        logger.error("Copilot runtime failed to start", { error: messageOf(error) });
        throw new CopilotRuntimeUnavailableError(`Copilot runtime failed to start: ${messageOf(error)}`, {
          cause: error,
        });
      }
      if (state !== "starting") {
        // stop() was called while starting.
        await candidate.stop().catch(() => undefined);
        throw new CopilotShuttingDownError();
      }
      client = candidate;
      state = "running";
      logger.info("Copilot runtime started");
      return candidate;
    })();
    startPromise = attempt;
    attempt.then(
      () => (startPromise = undefined),
      () => (startPromise = undefined),
    );
    return attempt;
  };

  const capacity = (): CopilotCapacity => ({
    maxConcurrentTurns: maxConcurrent,
    activeTurns: admission.activeCount,
    maxQueuedTurns: maxQueued,
    queuedTurns: admission.queuedCount,
  });

  const runtime: CopilotRuntime = {
    options,
    logger,
    admission,
    locks,
    conversationWaitMs,
    responseTimeout,
    approvalWindow,
    acquireClient: start,
    registerTurn(controller) {
      turns.add(controller);
      return () => turns.delete(controller);
    },
  };

  const agents = new WeakMap<CopilotAgentDefinition, AgentHandle>();

  const stop = async (stopOptions: { gracePeriodMs?: number } = {}): Promise<void> => {
    if (stopPromise) return stopPromise;
    const grace = stopOptions.gracePeriodMs ?? DEFAULT_SHUTDOWN_GRACE_MS;
    stopPromise = (async () => {
      state = "stopping";
      admission.close();
      const drained = await admission.whenIdle(grace);
      if (!drained) {
        logger.warn("Aborting in-flight Copilot turns after the shutdown grace period", { turns: turns.size });
        for (const turn of [...turns]) turn.abort(new CopilotShuttingDownError());
        await admission.whenIdle(ABORT_SETTLE_MS);
      }
      if (startPromise) await startPromise.catch(() => undefined);
      const current = client;
      client = undefined;
      if (current) {
        try {
          const errors = await current.stop();
          for (const error of errors ?? []) logger.warn("Error while stopping the Copilot runtime", { error: messageOf(error) });
        } catch (error) {
          logger.warn("Error while stopping the Copilot runtime", { error: messageOf(error) });
        }
      }
      state = "stopped";
    })();
    return stopPromise;
  };

  return {
    get state() {
      return state;
    },
    async start() {
      await start();
    },
    stop,
    client: start,
    agent(definition) {
      let handle = agents.get(definition);
      if (!handle) {
        handle = createAgentHandle(runtime, definition);
        agents.set(definition, handle);
      }
      return handle;
    },
    capacity,
    async health() {
      let version: string | undefined;
      const current = client;
      if (state === "running" && current?.getStatus) {
        try {
          const status = await current.getStatus();
          version = typeof status?.version === "string" ? status.version : undefined;
        } catch (error) {
          logger.debug("Copilot status lookup failed", { error: messageOf(error) });
        }
      }
      return { state, version, capacity: capacity() };
    },
  };
}

function messageOf(error: unknown): string {
  if (error instanceof CopilotIntegrationError) return error.message;
  if (error instanceof Error) return error.message;
  return String(error);
}
