import { createTestCopilot, type CreateTestCopilotOptions, type TestCopilot } from "@copilot-sdk-integrations/agent-core/testing";
import Fastify, { type FastifyInstance } from "fastify";
import { afterEach } from "vitest";
import { copilotPlugin, type CopilotPluginOptions } from "../src/index.js";

const cleanup: (() => Promise<unknown>)[] = [];

afterEach(async () => {
  for (const fn of cleanup.splice(0).reverse()) await fn().catch(() => undefined);
});

/** A test copilot stopped after the test. */
export function testCopilot(options: CreateTestCopilotOptions = {}): TestCopilot {
  const test = createTestCopilot(options);
  cleanup.push(() => test.stop({ gracePeriodMs: 0 }));
  return test;
}

/** A Fastify app (closed after the test). */
export function app(): FastifyInstance {
  const instance = Fastify();
  cleanup.push(() => instance.close());
  return instance;
}

/** An app with `copilotPlugin` registered against a test copilot. */
export async function setup(
  options: Omit<CopilotPluginOptions, "client"> = {},
  testOptions: CreateTestCopilotOptions = {},
  configure?: (fastify: FastifyInstance) => void | Promise<void>,
): Promise<{ fastify: FastifyInstance; test: TestCopilot }> {
  const test = testCopilot(testOptions);
  const fastify = app();
  await configure?.(fastify);
  await fastify.register(copilotPlugin, { ...options, client: test.copilot });
  return { fastify, test };
}

export const waitFor = async (predicate: () => boolean, timeoutMs = 2000): Promise<void> => {
  const start = Date.now();
  while (!predicate()) {
    if (Date.now() - start > timeoutMs) throw new Error("waitFor timed out");
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
};
