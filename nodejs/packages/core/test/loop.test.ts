import { afterEach, describe, expect, it } from "vitest";
import { defineAgent } from "../src/define-agent.js";
import { CopilotConfigurationError, CopilotTimeoutError } from "../src/errors.js";
import { runCopilotLoop, type CopilotLoopIteration } from "../src/loop.js";
import { createTestCopilot, type TestCopilot } from "../src/testing/index.js";

const builder = defineAgent({ name: "builder", instructions: "Pick the next task from IMPLEMENTATION_PLAN.md." });

let current: TestCopilot | undefined;
afterEach(async () => {
  await current?.stop({ gracePeriodMs: 0 });
  current = undefined;
});

describe("runCopilotLoop with an agent", () => {
  it("runs every iteration in a fresh ephemeral session", async () => {
    current = createTestCopilot({ script: (rt) => rt.onPrompt(/.*/).reply("done") });
    const { copilot, runtime } = current;

    const result = await runCopilotLoop(copilot.agent(builder), { prompt: "build", maxIterations: 3 });

    expect(result.stopReason).toBe("max-iterations");
    expect(result.iterations.map((i) => i.output)).toEqual(["done", "done", "done"]);
    expect(runtime.prompts).toEqual(["build", "build", "build"]);
    const ids = runtime.createdSessions.map((config) => config.sessionId);
    expect(ids).toHaveLength(3);
    expect(new Set(ids).size).toBe(3);
  });

  it("stops when the stop condition matches", async () => {
    let n = 0;
    current = createTestCopilot({ script: (rt) => rt.onPrompt(/.*/).replyWith(() => (++n === 2 ? "ALL DONE" : "working")) });

    const result = await runCopilotLoop(current.copilot.agent(builder), {
      prompt: "build",
      maxIterations: 5,
      stopWhen: (it) => it.output?.includes("ALL DONE") ?? false,
    });

    expect(result.stopReason).toBe("stop-condition");
    expect(result.iterations).toHaveLength(2);
  });

  it("fails a timed-out iteration", async () => {
    current = createTestCopilot({ script: (rt) => rt.onPrompt(/.*/).delay(5_000).reply("late") });

    const result = await runCopilotLoop(current.copilot.agent(builder), {
      prompt: "build",
      maxIterations: 2,
      iterationTimeout: 50,
    });

    expect(result.stopReason).toBe("failed");
    expect(result.iterations[0]!.error).toBeInstanceOf(CopilotTimeoutError);
  });
});

describe("runCopilotLoop with a step", () => {
  it("checks between iterations, not after the last", async () => {
    const checked: number[] = [];
    const stopped = await runCopilotLoop(() => "ok", {
      prompt: "p",
      maxIterations: 5,
      check: (previous) => {
        checked.push(previous.number);
        return previous.number < 2;
      },
    });
    expect(stopped.stopReason).toBe("check");
    expect(checked).toEqual([1, 2]);

    const all: number[] = [];
    await runCopilotLoop(() => "ok", { prompt: "p", maxIterations: 2, check: (p) => (all.push(p.number), true) });
    expect(all).toEqual([1]);
  });

  it("stops on failure unless continueOnFailure", async () => {
    const step = ({ iteration }: { iteration: number }) => {
      if (iteration === 1) throw new Error("boom");
      return "ok";
    };
    const failed = await runCopilotLoop(step, { prompt: "p", maxIterations: 3 });
    expect(failed.stopReason).toBe("failed");
    expect((failed.iterations[0]!.error as Error).message).toBe("boom");

    const continued = await runCopilotLoop(step, { prompt: "p", maxIterations: 3, continueOnFailure: true });
    expect(continued.stopReason).toBe("max-iterations");
    expect(continued.iterations.map((i) => i.error === undefined)).toEqual([false, true, true]);
  });

  it("cancels through the signal, aborting the running iteration", async () => {
    const controller = new AbortController();
    let aborted = false;
    const result = await runCopilotLoop(
      ({ iteration, signal }) =>
        new Promise<string>((resolve) => {
          if (iteration === 2) {
            signal.addEventListener("abort", () => (aborted = true));
            controller.abort();
            return;
          }
          resolve("ok");
        }),
      { prompt: "p", maxIterations: 5, signal: controller.signal },
    );
    expect(result.stopReason).toBe("cancelled");
    expect(result.iterations).toHaveLength(1);
    expect(aborted).toBe(true);

    const before = await runCopilotLoop(() => "x", { prompt: "p", maxIterations: 1, signal: AbortSignal.abort() });
    expect(before).toEqual({ stopReason: "cancelled", iterations: [] });
  });

  it("calls the prompt function per iteration and notifies listeners", async () => {
    const seen: CopilotLoopIteration[] = [];
    await runCopilotLoop(({ prompt }) => prompt.toUpperCase(), {
      prompt: (n) => `prompt ${n}`,
      maxIterations: 2,
      onIteration: (it) => {
        seen.push(it);
        throw new Error("ignored");
      },
    });
    expect(seen.map((i) => i.output)).toEqual(["PROMPT 1", "PROMPT 2"]);
  });

  it("validates options", async () => {
    await expect(runCopilotLoop(() => "x", { prompt: "p", maxIterations: 0 })).rejects.toBeInstanceOf(
      CopilotConfigurationError,
    );
    await expect(
      runCopilotLoop(() => "x", { prompt: "p", maxIterations: 1, iterationTimeout: 0 }),
    ).rejects.toBeInstanceOf(CopilotConfigurationError);
  });
});
