import { afterEach, describe, expect, it, vi } from "vitest";
import { CopilotTimeoutError, defineAgent, describeAgent, formatSseEvent, toSseStream, type CopilotAgentEvent } from "../src/index.js";
import { parseSse } from "../src/testing/index.js";

async function readAll(stream: ReadableStream<Uint8Array>): Promise<string> {
  const decoder = new TextDecoder();
  let text = "";
  for await (const chunk of stream as unknown as AsyncIterable<Uint8Array>) text += decoder.decode(chunk, { stream: true });
  return text + decoder.decode();
}

async function* from(events: CopilotAgentEvent[], error?: unknown): AsyncGenerator<CopilotAgentEvent> {
  for (const event of events) yield event;
  if (error !== undefined) throw error;
}

afterEach(() => {
  vi.useRealTimers();
});

describe("toSseStream", () => {
  it("encodes events as SSE frames", async () => {
    const text = await readAll(
      toSseStream(
        from([
          { type: "turn-start", sessionId: "s1" },
          { type: "text-delta", delta: "Hi" },
          { type: "done", text: "Hi", sessionId: "s1" },
        ]),
        { heartbeatMs: 0 },
      ),
    );
    expect(text).toBe(
      'event: turn-start\ndata: {"type":"turn-start","sessionId":"s1"}\n\n' +
        'event: text-delta\ndata: {"type":"text-delta","delta":"Hi"}\n\n' +
        'event: done\ndata: {"type":"done","text":"Hi","sessionId":"s1"}\n\n',
    );
    expect(parseSse(text).map((frame) => frame.event)).toEqual(["turn-start", "text-delta", "done"]);
  });

  it("drops the respond callback of permission requests and flattens error events", () => {
    const permission = formatSseEvent({
      type: "permission-request",
      requestId: "p1",
      request: { kind: "shell" } as never,
      respond: () => {},
    });
    expect(parseSse(permission)).toEqual([
      { event: "permission-request", data: { type: "permission-request", requestId: "p1", request: { kind: "shell" } } },
    ]);
    const error = formatSseEvent({ type: "error", error: new CopilotTimeoutError(5) });
    expect(parseSse(error)[0]!.data).toEqual({ type: "error", error: { name: "CopilotTimeoutError", message: expect.any(String) } });
  });

  it("turns a thrown error into a final error frame (generic message for non-Copilot errors)", async () => {
    const copilot = parseSse(await readAll(toSseStream(from([{ type: "text-delta", delta: "a" }], new CopilotTimeoutError(5)), { heartbeatMs: 0 })));
    expect(copilot.at(-1)).toEqual({
      event: "error",
      data: { type: "error", error: { name: "CopilotTimeoutError", code: "COPILOT_TIMEOUT", message: new CopilotTimeoutError(5).message } },
    });
    const other = parseSse(await readAll(toSseStream(from([], new Error("secret")), { heartbeatMs: 0 })));
    expect(other).toEqual([{ event: "error", data: { type: "error", error: { name: "Error", code: "ERROR", message: "An error occurred." } } }]);
    const custom = parseSse(await readAll(toSseStream(from([], new Error("secret")), { heartbeatMs: 0, onError: () => "custom" })));
    expect(custom[0]!.data.error.message).toBe("custom");
  });

  it("sends heartbeat comments while idle and stops them when finished", async () => {
    vi.useFakeTimers();
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    async function* slow(): AsyncGenerator<CopilotAgentEvent> {
      await gate;
      yield { type: "text-delta", delta: "x" };
    }
    const reader = toSseStream(slow(), { heartbeatMs: 10 }).getReader();
    const decoder = new TextDecoder();
    const pending = reader.read();
    await vi.advanceTimersByTimeAsync(10);
    expect(decoder.decode((await pending).value)).toBe(": heartbeat\n\n");
    release();
    let text = "";
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      text += decoder.decode(value);
    }
    expect(text.replaceAll(": heartbeat\n\n", "")).toBe('event: text-delta\ndata: {"type":"text-delta","delta":"x"}\n\n');
    expect(vi.getTimerCount()).toBe(0);
  });

  it("rejects an invalid heartbeat interval", () => {
    expect(() => toSseStream(from([]), { heartbeatMs: -1 })).toThrow(RangeError);
  });

  it("calls the iterator's return() when the stream is cancelled", async () => {
    const returned = vi.fn();
    const iterable: AsyncIterable<CopilotAgentEvent> = {
      [Symbol.asyncIterator]: () => ({
        next: async () => ({ done: false, value: { type: "text-delta", delta: "x" } }),
        return: async () => {
          returned();
          return { done: true, value: undefined };
        },
      }),
    };
    const stream = toSseStream(iterable, { heartbeatMs: 0 });
    const reader = stream.getReader();
    await reader.read();
    await reader.cancel();
    await Promise.resolve();
    expect(returned).toHaveBeenCalledOnce();
  });
});

describe("parseSse", () => {
  it("parses named, unnamed, multi-line, and non-JSON frames and skips comments", () => {
    const text = ": hi\n\nevent: a\nid: 1\ndata: {\"x\":1}\n\ndata: line1\r\ndata: line2\r\n\r\ndata: [DONE]\n\nevent: empty\n\n";
    expect(parseSse(text)).toEqual([
      { event: "a", id: "1", data: { x: 1 } },
      { data: "line1\nline2" },
      { data: "[DONE]" },
    ]);
  });
});

describe("describeAgent", () => {
  const tool = { name: "lookup", description: "d", execute: () => "x" };
  const def = defineAgent({
    name: "support",
    description: "Helps",
    model: "gpt-5",
    instructions: "secret prompt",
    tools: [tool],
    mcpServers: { github: { type: "http", url: "https://example.com", headers: { Authorization: "x" } } as never },
    provider: { type: "openai", baseUrl: "https://x", apiKey: "k" } as never,
  });

  it("describes an agent without secrets or instructions", () => {
    const info = describeAgent(def);
    expect(info).toEqual({
      name: "support",
      description: "Helps",
      model: "gpt-5",
      provider: "openai",
      builtInTools: false,
      tools: ["lookup"],
      mcpServers: ["github"],
    });
    expect(JSON.stringify(info)).not.toContain("secret");
  });

  it("names a configured provider and optionally includes instructions", () => {
    const info = describeAgent(def, { providers: { primary: def.provider! }, includeInstructions: true });
    expect(info.provider).toBe("primary");
    expect(info.instructions).toBe("secret prompt");
  });
});
