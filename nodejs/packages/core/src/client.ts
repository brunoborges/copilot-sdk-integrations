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
export const DEFAULT_RESTART_BACKOFF_MS = 1_000;
export const DEFAULT_RESTART_MAX_BACKOFF_MS = 30_000;
export const DEFAULT_CONNECTION_CHECK_INTERVAL_MS = 1_000;
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
  /**
   * The started client (lazy start, shared in-flight start, restart after a crash). Pass the turn's
   * registered controller so the turn is aborted if that client's connection is lost.
   */
  acquireClient(turn?: AbortController): Promise<ClientLike>;
  /** Marks the runtime failed if the client's connection was lost (called after a turn fails). */
  checkConnection(client: ClientLike): void;
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

/**
 * Whether the client's connection to the runtime is gone. Uses `isConnected()` when the client has
 * it; the SDK's `CopilotClient` has no public state, but its `rpc` accessor throws once disconnected.
 */
export function isConnectionLost(client: ClientLike): boolean {
  try {
    if (typeof client.isConnected === "function") return !client.isConnected();
    if ("rpc" in client) void (client as { rpc: unknown }).rpc;
    return false;
  } catch {
    return true;
  }
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
  const restart = options.restart ?? {};
  const maxAttempts = restart.maxAttempts ?? Number.POSITIVE_INFINITY;
  if (maxAttempts !== Number.POSITIVE_INFINITY && (!Number.isInteger(maxAttempts) || maxAttempts < 0)) {
    throw new CopilotConfigurationError("restart.maxAttempts must be a non-negative integer or Infinity");
  }
  const backoffMs = positiveMs("restart.backoffMs", restart.backoffMs, DEFAULT_RESTART_BACKOFF_MS);
  const maxBackoffMs = positiveMs("restart.maxBackoffMs", restart.maxBackoffMs, DEFAULT_RESTART_MAX_BACKOFF_MS);
  const checkIntervalMs = positiveMs("restart.checkIntervalMs", restart.checkIntervalMs, DEFAULT_CONNECTION_CHECK_INTERVAL_MS);
  if (options.startup !== undefined && options.startup !== "lazy" && options.startup !== "eager") {
    throw new CopilotConfigurationError(`startup must be "lazy" or "eager"`);
  }
  if (options.cliUrl !== undefined && options.cliPath !== undefined) {
    throw new CopilotConfigurationError("cliUrl and cliPath are mutually exclusive");
  }
  const factory = options.clientFactory ?? defaultClientFactory;

  const admission = new AdmissionController(maxConcurrent, maxQueued);
  const locks = new ConversationLocks();
  /** Registered turns and the client each one uses. */
  const turns = new Map<AbortController, ClientLike | undefined>();

  let state: CopilotClientState = "not-started";
  let client: ClientLike | undefined;
  let startPromise: Promise<ClientLike> | undefined;
  let stopPromise: Promise<void> | undefined;
  /** Consecutive failed start attempts. */
  let failedStarts = 0;
  /** Consecutive failures (failed starts, plus one for a lost connection), for the backoff. */
  let failures = 0;
  let lastFailureAt = 0;
  let monitor: NodeJS.Timeout | undefined;
  let cancelBackoff: (() => void) | undefined;

  const stopMonitor = () => {
    if (monitor) clearInterval(monitor);
    monitor = undefined;
  };

  const connectionLost = (lost: ClientLike) => {
    if (client !== lost || state !== "running") return;
    client = undefined;
    state = "failed";
    failures = 1;
    failedStarts = 0;
    lastFailureAt = Date.now();
    stopMonitor();
    logger.error("Copilot runtime connection lost; it restarts on the next turn", { turns: turns.size });
    const error = new CopilotRuntimeUnavailableError("Copilot runtime connection lost");
    for (const [turn, used] of [...turns]) if (used === lost) turn.abort(error);
    void lost.stop().catch(() => undefined);
  };

  const checkConnection = (candidate: ClientLike) => {
    if (client === candidate && state === "running" && isConnectionLost(candidate)) connectionLost(candidate);
  };

  const startMonitor = (current: ClientLike) => {
    stopMonitor();
    monitor = setInterval(() => checkConnection(current), checkIntervalMs);
    monitor.unref?.();
  };

  const backoffDelay = (): number => {
    if (failures <= 1) return 0;
    const delay = Math.min(backoffMs * 2 ** (failures - 2), maxBackoffMs);
    return Math.max(0, lastFailureAt + delay - Date.now());
  };

  const start = (explicit = false): Promise<ClientLike> => {
    if (state === "stopping" || state === "stopped") return Promise.reject(new CopilotShuttingDownError());
    if (state === "running" && client) {
      checkConnection(client);
      if (state === "running" && client) return Promise.resolve(client);
    }
    if (startPromise) return startPromise;
    if (state === "failed") {
      if (explicit) {
        failedStarts = 0;
        failures = 0;
      } else if (failedStarts >= maxAttempts) {
        return Promise.reject(
          new CopilotRuntimeUnavailableError(
            maxAttempts === 0
              ? "Copilot runtime is unavailable and automatic restarts are disabled; call start() to restart it"
              : `Copilot runtime is unavailable after ${failedStarts} failed start attempts; call start() to retry`,
          ),
        );
      }
    }
    const delay = state === "failed" ? backoffDelay() : 0;
    state = "starting";
    const attempt = (async () => {
      if (delay > 0) {
        logger.info("Waiting before restarting the Copilot runtime", { delayMs: delay, attempt: failedStarts + 1 });
        await new Promise<void>((resolve) => {
          const timer = setTimeout(resolve, delay);
          cancelBackoff = () => {
            clearTimeout(timer);
            resolve();
          };
        });
        cancelBackoff = undefined;
        if (state !== "starting") throw new CopilotShuttingDownError();
      }
      let candidate: ClientLike | undefined;
      try {
        let sdkOptions = buildClientOptions(options);
        if (options.configureClient) sdkOptions = options.configureClient(sdkOptions);
        candidate = factory(sdkOptions);
        await candidate.start();
      } catch (error) {
        if (state === "starting") state = "failed";
        failedStarts++;
        failures++;
        lastFailureAt = Date.now();
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
      failedStarts = 0;
      failures = 0;
      startMonitor(candidate);
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
    async acquireClient(turn) {
      const acquired = await start();
      if (turn && turns.has(turn)) turns.set(turn, acquired);
      return acquired;
    },
    checkConnection,
    registerTurn(controller) {
      turns.set(controller, undefined);
      return () => turns.delete(controller);
    },
  };

  const agents = new WeakMap<CopilotAgentDefinition, AgentHandle>();

  const stop = async (stopOptions: { gracePeriodMs?: number } = {}): Promise<void> => {
    if (stopPromise) return stopPromise;
    const grace = stopOptions.gracePeriodMs ?? DEFAULT_SHUTDOWN_GRACE_MS;
    stopPromise = (async () => {
      state = "stopping";
      stopMonitor();
      cancelBackoff?.();
      admission.close();
      const drained = await admission.whenIdle(grace);
      if (!drained) {
        logger.warn("Aborting in-flight Copilot turns after the shutdown grace period", { turns: turns.size });
        for (const turn of [...turns.keys()]) turn.abort(new CopilotShuttingDownError());
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
      await start(true);
    },
    stop,
    client: () => start(),
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
      if (current) checkConnection(current);
      if (state === "running" && current?.getStatus) {
        try {
          const status = await current.getStatus();
          version = typeof status?.version === "string" ? status.version : undefined;
        } catch (error) {
          logger.debug("Copilot status lookup failed", { error: messageOf(error) });
          checkConnection(current);
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
