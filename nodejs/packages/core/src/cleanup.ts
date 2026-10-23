import { CopilotConfigurationError } from "./errors.js";
import { sessionCleanupInterval } from "./sessions.js";
import type { CopilotLogger, CopilotSessions } from "./types.js";

/** Periodic deletion of old sessions. Disabled unless `maxAge` is set. */
export interface CopilotSessionCleanupOptions {
  /** Delete sessions created by this library that were not modified for this long (ms). */
  maxAge: number;
  /** Sweep interval (ms). Default: half of `maxAge`, clamped to between one minute and one hour. */
  interval?: number;
}

export interface SessionCleanup {
  readonly maxAge: number;
  readonly interval: number;
  /** Starts the periodic sweep (idempotent). The timer is `unref()`'d so it never keeps the process alive. */
  start(): void;
  /** Stops the periodic sweep (idempotent). */
  stop(): void;
  /** Runs one sweep now and returns the number of sessions deleted (see `CopilotSessions.deleteExpired`). */
  sweep(): Promise<number>;
}

/** Validates `sessionCleanup` options; errors name the offending key. */
export function validateSessionCleanupOptions(options: CopilotSessionCleanupOptions, name = "sessionCleanup"): void {
  if (!options || typeof options !== "object") throw new CopilotConfigurationError(`${name} must be an object`);
  const { maxAge, interval } = options;
  if (typeof maxAge !== "number" || !Number.isFinite(maxAge) || maxAge <= 0) {
    throw new CopilotConfigurationError(`${name}.maxAge must be a positive number of milliseconds`);
  }
  if (interval !== undefined && (typeof interval !== "number" || !Number.isFinite(interval) || interval <= 0)) {
    throw new CopilotConfigurationError(`${name}.interval must be a positive number of milliseconds`);
  }
}

/**
 * Creates a job that periodically deletes sessions created by this library that were not modified
 * within `maxAge` (`copilot.sessions.deleteExpired`). Framework integrations start it when the
 * application is ready and stop it on shutdown. A sweep never starts the runtime; failed sweeps are
 * logged and retried at the next interval.
 */
export function createSessionCleanup(
  sessions: CopilotSessions,
  options: CopilotSessionCleanupOptions,
  logger?: CopilotLogger,
): SessionCleanup {
  validateSessionCleanupOptions(options);
  const maxAge = options.maxAge;
  const interval = options.interval ?? sessionCleanupInterval(maxAge);
  let timer: NodeJS.Timeout | undefined;
  let running: Promise<number> | undefined;

  const sweep = (): Promise<number> => {
    running ??= (async () => {
      try {
        const deleted = await sessions.deleteExpired(maxAge);
        if (deleted > 0) logger?.info("Deleted expired Copilot sessions", { deleted, maxAgeMs: maxAge });
        return deleted;
      } finally {
        running = undefined;
      }
    })();
    return running;
  };

  return {
    maxAge,
    interval,
    start() {
      if (timer) return;
      timer = setInterval(() => {
        sweep().catch((error: unknown) =>
          logger?.warn("Copilot session cleanup failed; retrying at the next sweep", {
            error: error instanceof Error ? error.message : String(error),
          }),
        );
      }, interval);
      timer.unref?.();
    },
    stop() {
      if (timer) clearInterval(timer);
      timer = undefined;
    },
    sweep,
  };
}
