import { createTestCopilot, type CopilotTestRuntime, type CreateTestCopilotOptions, type TestCopilot } from "@copilot-sdk-integrations/agent-core/testing";
import type { Copilot } from "@copilot-sdk-integrations/agent-core";
import type { FastifyInstance } from "fastify";

export { parseSse, type SseFrame } from "@copilot-sdk-integrations/agent-core/testing";

export interface CopilotTestApp<App extends FastifyInstance = FastifyInstance> {
  app: App;
  /** The scripted runtime (`onPrompt(...)`, `crash()`, `prompts`, `toolCalls`, ...). */
  runtime: CopilotTestRuntime;
  copilot: Copilot;
}

/**
 * Builds an app against a scripted Copilot runtime. `build` receives `{ client }` to pass to
 * `copilotPlugin` (or `copilotPlugin.scoped`); the test client is stopped when the app closes.
 *
 * ```ts
 * const { app, runtime } = await buildCopilotTestApp((opts) => buildApp(opts), (rt) => rt.onPrompt(/hi/).reply("hello"));
 * ```
 */
export async function buildCopilotTestApp<App extends FastifyInstance>(
  build: (options: { client: Copilot }) => App | Promise<App>,
  script?: (runtime: CopilotTestRuntime) => void,
  options: Omit<CreateTestCopilotOptions, "script"> = {},
): Promise<CopilotTestApp<App>> {
  const test: TestCopilot = createTestCopilot({ ...options, ...(script ? { script } : {}) });
  let app: App;
  try {
    app = await build({ client: test.copilot });
  } catch (error) {
    await test.stop({ gracePeriodMs: 0 });
    throw error;
  }
  app.addHook("onClose", async () => {
    await test.stop({ gracePeriodMs: 0 });
  });
  return { app, runtime: test.runtime, copilot: test.copilot };
}
