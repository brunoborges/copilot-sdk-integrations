import { tmpdir } from "node:os";
import { join } from "node:path";
import { createCopilot } from "../client.js";
import type { Copilot, CopilotLogger, CopilotOptions } from "../types.js";
import { CopilotTestRuntime, type CopilotTestRuntimeOptions } from "./runtime.js";

/** Defaults applied by `createTestCopilot` (each can be overridden). */
export const TEST_COPILOT_DEFAULTS = {
  /** Never read: the scripted runtime has no disk state. Keeps `~/.copilot` out of the picture. */
  baseDirectory: join(tmpdir(), "copilot-sdk-integrations-test"),
  responseTimeout: 10_000,
  approvalWindow: 5_000,
  conversationWaitMs: 5_000,
  shutdownGracePeriodMs: 1_000,
} as const;

export interface CreateTestCopilotOptions
  extends Omit<CopilotOptions, "clientFactory" | "cliPath" | "cliUrl">,
    CopilotTestRuntimeOptions {
  /** Reuse an existing runtime (for example, one shared by a test module). Default: a new runtime. */
  runtime?: CopilotTestRuntime;
  /** Register scripted replies before the copilot is created. */
  script?: (runtime: CopilotTestRuntime) => unknown;
}

export interface TestCopilot extends AsyncDisposable {
  copilot: Copilot;
  runtime: CopilotTestRuntime;
  /** Stops the copilot (short grace period by default). Idempotent. */
  stop(options?: { gracePeriodMs?: number }): Promise<void>;
}

/** A logger that drops everything (the default for `createTestCopilot`). */
export function silentLogger(): CopilotLogger {
  const noop = () => {};
  return { debug: noop, info: noop, warn: noop, error: noop };
}

/**
 * Creates a `Copilot` backed by a scripted `CopilotTestRuntime`: no runtime process,
 * credentials, or paid requests.
 *
 * ```ts
 * await using test = createTestCopilot({ script: (rt) => rt.onPrompt(/.*\/).reply("Hello") });
 * await test.copilot.agent(myAgent).send({ prompt: "Hi" }); // "Hello"
 * test.runtime.prompts; // ["Hi"]
 * ```
 *
 * Test-friendly defaults: a silent logger, a throwaway `baseDirectory`, a 10 s response timeout,
 * a 5 s approval window and conversation wait, and a 1 s shutdown grace period.
 */
export function createTestCopilot(options: CreateTestCopilotOptions = {}): TestCopilot {
  const { runtime: provided, script, models, defaultModel, ...copilotOptions } = options;
  const runtimeOptions: CopilotTestRuntimeOptions = {};
  if (models !== undefined) runtimeOptions.models = models;
  if (defaultModel !== undefined) runtimeOptions.defaultModel = defaultModel;
  const runtime = provided ?? new CopilotTestRuntime(runtimeOptions);
  if (provided && models !== undefined) provided.setModels(models);
  script?.(runtime);

  const copilot = createCopilot({
    baseDirectory: TEST_COPILOT_DEFAULTS.baseDirectory,
    responseTimeout: TEST_COPILOT_DEFAULTS.responseTimeout,
    approvalWindow: TEST_COPILOT_DEFAULTS.approvalWindow,
    logger: silentLogger(),
    ...copilotOptions,
    limits: { conversationWaitMs: TEST_COPILOT_DEFAULTS.conversationWaitMs, ...copilotOptions.limits },
    clientFactory: runtime.clientFactory,
  });

  const stop = (stopOptions: { gracePeriodMs?: number } = {}) =>
    copilot.stop({ gracePeriodMs: stopOptions.gracePeriodMs ?? TEST_COPILOT_DEFAULTS.shutdownGracePeriodMs });

  return {
    copilot,
    runtime,
    stop,
    [Symbol.asyncDispose]: () => stop(),
  };
}
