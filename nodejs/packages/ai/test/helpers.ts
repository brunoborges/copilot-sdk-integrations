import { afterEach } from "vitest";
import type { UIMessageChunk } from "ai";
import { createTestCopilot, type CreateTestCopilotOptions, type TestCopilot } from "@copilot-sdk-integrations/core/testing";

let current: TestCopilot[] = [];

export function setup(options: CreateTestCopilotOptions = {}): TestCopilot {
  const test = createTestCopilot(options);
  current.push(test);
  return test;
}

afterEach(async () => {
  const tests = current;
  current = [];
  await Promise.all(tests.map((test) => test.stop({ gracePeriodMs: 0 })));
});

export async function collect<T>(stream: ReadableStream<T>): Promise<T[]> {
  const out: T[] = [];
  const reader = stream.getReader();
  for (;;) {
    const { value, done } = await reader.read();
    if (done) return out;
    out.push(value);
  }
}

export function types(chunks: UIMessageChunk[]): string[] {
  return chunks.map((chunk) => chunk.type);
}
