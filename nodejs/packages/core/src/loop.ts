import { CopilotAbortedError, CopilotConfigurationError, CopilotTimeoutError } from "./errors.js";
import type { AgentHandle } from "./types.js";

/** One completed loop iteration. */
export interface CopilotLoopIteration {
  /** 1-based iteration number. */
  number: number;
  prompt: string;
  /** The reply text; undefined when the iteration failed. */
  output?: string;
  /** Why the iteration failed (including `CopilotTimeoutError`); undefined on success. */
  error?: unknown;
  durationMs: number;
}

export type CopilotLoopStopReason = "stop-condition" | "check" | "max-iterations" | "failed" | "cancelled";

export interface CopilotLoopResult {
  stopReason: CopilotLoopStopReason;
  /** Every completed iteration, in order. */
  iterations: CopilotLoopIteration[];
}

/** Runs one iteration in a fresh session and returns its output. Must honor `signal`. */
export type CopilotLoopStep = (input: {
  iteration: number;
  prompt: string;
  signal: AbortSignal;
  timeout?: number;
}) => Promise<string | undefined> | string | undefined;

export interface CopilotLoopOptions {
  /** Sent on every iteration; a function is called before each one (for example to re-read `PROMPT.md`). */
  prompt: string | ((iteration: number) => string | Promise<string>);
  /** Upper bound on iterations; at least 1. */
  maxIterations: number;
  /** Per-iteration timeout in ms; a timed-out iteration fails with `CopilotTimeoutError`. */
  iterationTimeout?: number;
  /** Quality gate between iterations (for example run the tests); return false to stop with "check". */
  check?: (previous: CopilotLoopIteration) => boolean | Promise<boolean>;
  /** Stops after a successful iteration that matches, with "stop-condition". */
  stopWhen?: (iteration: CopilotLoopIteration) => boolean | Promise<boolean>;
  /** Observes each completed iteration; errors are ignored. */
  onIteration?: (iteration: CopilotLoopIteration) => void;
  /** Aborting stops the loop (and the running iteration) with "cancelled". */
  signal?: AbortSignal;
  /** Record a failed iteration and keep going instead of stopping with "failed". Default false. */
  continueOnFailure?: boolean;
}

/**
 * Runs the same prompt repeatedly, each iteration in a fresh session (the "Ralph loop" pattern).
 *
 * With an `AgentHandle`, every iteration is an ordinary ephemeral `send` (no `conversationId`), so the
 * agent's permission policy, tools, and hooks apply unchanged and nothing is approved implicitly.
 * State lives on disk in the agent's working directory, not in model context.
 */
export async function runCopilotLoop(
  agent: AgentHandle | CopilotLoopStep,
  options: CopilotLoopOptions,
): Promise<CopilotLoopResult> {
  validate(options);
  const step: CopilotLoopStep =
    typeof agent === "function"
      ? agent
      : ({ prompt, signal, timeout }) =>
          agent.send(timeout === undefined ? { prompt, signal } : { prompt, signal, timeout });
  const { maxIterations, signal } = options;
  const iterations: CopilotLoopIteration[] = [];
  const result = (stopReason: CopilotLoopStopReason): CopilotLoopResult => ({ stopReason, iterations });

  for (let number = 1; number <= maxIterations; number++) {
    if (signal?.aborted) return result("cancelled");
    const prompt = typeof options.prompt === "function" ? await options.prompt(number) : options.prompt;
    if (typeof prompt !== "string") throw new CopilotConfigurationError("Copilot loop prompt must be a string");
    const iteration = await execute(step, number, prompt, options);
    if (signal?.aborted) return result("cancelled");
    iterations.push(iteration);
    try {
      options.onIteration?.(iteration);
    } catch {
      // Listeners observe; they never change the loop's outcome.
    }
    const succeeded = iteration.error === undefined;
    if (!succeeded && !options.continueOnFailure) return result("failed");
    if (succeeded && options.stopWhen && (await options.stopWhen(iteration))) return result("stop-condition");
    if (number < maxIterations && options.check && !(await options.check(iteration))) return result("check");
  }
  return result("max-iterations");
}

async function execute(
  step: CopilotLoopStep,
  number: number,
  prompt: string,
  options: CopilotLoopOptions,
): Promise<CopilotLoopIteration> {
  const started = performance.now();
  const controller = new AbortController();
  const timeout = options.iterationTimeout;
  const onAbort = () => controller.abort(options.signal?.reason ?? new CopilotAbortedError());
  options.signal?.addEventListener("abort", onAbort, { once: true });
  let timer: ReturnType<typeof setTimeout> | undefined;
  const stopped = new Promise<never>((_, reject) => {
    controller.signal.addEventListener("abort", () => reject(controller.signal.reason), { once: true });
    if (timeout !== undefined) {
      timer = setTimeout(() => controller.abort(new CopilotTimeoutError(timeout)), timeout);
    }
  });
  stopped.catch(() => {});
  try {
    const run = Promise.resolve().then(() => step({ iteration: number, prompt, signal: controller.signal, timeout }));
    const output = await Promise.race([run, stopped]);
    return { number, prompt, output: output ?? undefined, durationMs: performance.now() - started };
  } catch (error) {
    return { number, prompt, error, durationMs: performance.now() - started };
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener("abort", onAbort);
  }
}

function validate(options: CopilotLoopOptions): void {
  if (!options || (typeof options.prompt !== "string" && typeof options.prompt !== "function")) {
    throw new CopilotConfigurationError("Copilot loop requires a prompt");
  }
  if (!Number.isInteger(options.maxIterations) || options.maxIterations < 1) {
    throw new CopilotConfigurationError("Copilot loop maxIterations must be an integer of at least 1");
  }
  if (options.iterationTimeout !== undefined && !(options.iterationTimeout > 0)) {
    throw new CopilotConfigurationError("Copilot loop iterationTimeout must be positive");
  }
}
