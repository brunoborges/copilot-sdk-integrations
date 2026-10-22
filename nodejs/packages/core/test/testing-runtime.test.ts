import { describe, expect, it, beforeEach } from "vitest";
import { z } from "zod";
import type { PermissionHandler, SessionConfig, SessionEvent, Tool } from "@github/copilot-sdk";
import { CopilotTestRuntime, type TestSession } from "../src/testing/index.js";
import type { ClientLike, SessionLike } from "../src/types.js";

const approve: PermissionHandler = () => ({ kind: "approve-once" });
const reject: PermissionHandler = () => ({ kind: "reject", feedback: "nope" });

function lookupOrderTool(overrides: Partial<Tool<{ id: string }>> = {}): Tool<{ id: string }> {
  return {
    name: "lookupOrder",
    description: "Looks up an order",
    handler: (args) => ({ id: args.id, status: "shipped" }),
    ...overrides,
  };
}

/** Collects events and resolves once the turn ends (session.idle). */
function collect(session: SessionLike): { events: SessionEvent[]; idle: Promise<SessionEvent[]> } {
  const events: SessionEvent[] = [];
  let resolve!: (events: SessionEvent[]) => void;
  const idle = new Promise<SessionEvent[]>((r) => (resolve = r));
  session.on((event) => {
    events.push(event);
    if (event.type === "session.idle") resolve(events);
  });
  return { events, idle };
}

async function run(session: SessionLike, prompt: string): Promise<SessionEvent[]> {
  const { idle } = collect(session);
  await session.send({ prompt });
  return idle;
}

function types(events: SessionEvent[]): string[] {
  return events.map((e) => e.type);
}

function finalText(events: SessionEvent[]): string | undefined {
  const messages = events.filter((e) => e.type === "assistant.message");
  return messages.at(-1)?.data.content;
}

function waitFor(session: SessionLike, type: SessionEvent["type"]): Promise<SessionEvent> {
  return new Promise((resolve) => {
    const off = session.on((event) => {
      if (event.type === type) {
        off();
        resolve(event);
      }
    });
  });
}

describe("CopilotTestRuntime", () => {
  let runtime: CopilotTestRuntime;
  let client: ClientLike;

  beforeEach(() => {
    runtime = new CopilotTestRuntime();
    client = runtime.clientFactory({ workingDirectory: "/work" });
  });

  const newSession = (config: Partial<SessionConfig> = {}) =>
    client.createSession({ onPermissionRequest: approve, ...config } as SessionConfig);

  describe("client lifecycle", () => {
    it("records client options and start/stop state", async () => {
      expect(runtime.clientOptions).toEqual([{ workingDirectory: "/work" }]);
      expect(runtime.started).toBe(false);
      await client.start();
      expect(runtime.started).toBe(true);
      expect(await client.stop()).toEqual([]);
      expect(runtime.started).toBe(false);
      expect(runtime.stopped).toBe(true);
    });

    it("simulates start failures, limited by times", async () => {
      runtime.failStart(new Error("no runtime"), 1);
      await expect(client.start()).rejects.toThrow("no runtime");
      expect(runtime.started).toBe(false);
      await client.start();
      expect(runtime.started).toBe(true);
    });

    it("auto-starts on createSession and propagates start failures", async () => {
      runtime.failStart("boom");
      await expect(newSession()).rejects.toThrow("boom");
      runtime.failStart(undefined);
      await newSession();
      expect(runtime.started).toBe(true);
    });

    it("reports status and configurable models", async () => {
      expect(await client.getStatus!()).toMatchObject({ version: "test" });
      expect(await client.listModels!()).toEqual([{ id: "test-model", name: "Test Model" }]);
      runtime.setModels([{ id: "gpt-x" }]);
      expect(await client.listModels!()).toEqual([{ id: "gpt-x" }]);
    });

    it("stop disconnects sessions", async () => {
      runtime.onPrompt(/.*/).reply("hi");
      const session = await newSession();
      await client.stop();
      await expect(session.send({ prompt: "x" })).rejects.toThrow("Session is disconnected");
    });
  });

  describe("matching", () => {
    it("uses the first matching rule in registration order", async () => {
      runtime.onPrompt("order").reply("order rule").onPrompt(/.*/).reply("fallback");
      const session = await newSession();
      expect(finalText(await run(session, "Where is my order?"))).toBe("order rule");
      expect(finalText(await run(session, "Hello"))).toBe("fallback");
    });

    it("supports regex and predicate matchers", async () => {
      runtime.onPrompt((p) => p.startsWith("!")).reply("command");
      runtime.onPrompt(/^\d+$/).reply("number");
      const session = await newSession();
      expect(finalText(await run(session, "!go"))).toBe("command");
      expect(finalText(await run(session, "42"))).toBe("number");
    });

    it("consumes once/times rules and falls through", async () => {
      runtime.onPrompt(/.*/).reply("first").once();
      runtime.onPrompt(/.*/).reply("second").times(2);
      runtime.onPrompt(/.*/).reply("rest");
      const session = await newSession();
      const replies: (string | undefined)[] = [];
      for (let i = 0; i < 5; i++) replies.push(finalText(await run(session, `p${i}`)));
      expect(replies).toEqual(["first", "second", "second", "rest", "rest"]);
    });

    it("rejects invalid times", () => {
      expect(() => runtime.onPrompt("x").times(0)).toThrow(RangeError);
    });

    it("emits session.error listing the prompt when nothing matches", async () => {
      runtime.onPrompt("known").reply("ok");
      const session = await newSession();
      const events = await run(session, "something else");
      expect(types(events)).toEqual(["user.message", "session.error", "session.idle"]);
      const error = events[1] as Extract<SessionEvent, { type: "session.error" }>;
      expect(error.data.message).toContain('"something else"');
      expect(error.data.message).toContain("onPrompt");
    });
  });

  describe("events", () => {
    it("emits a realistic reply sequence with envelope fields", async () => {
      runtime.onPrompt(/.*/).reasoning("thinking").reply("Hello").usage({ inputTokens: 10, outputTokens: 2 });
      const session = await newSession({ model: "gpt-test" });
      const { idle } = collect(session);
      const messageId = await session.send({ prompt: "Hi" });
      const events = await idle;
      expect(types(events)).toEqual([
        "user.message",
        "assistant.turn_start",
        "assistant.reasoning_delta",
        "assistant.reasoning",
        "assistant.message",
        "assistant.usage",
        "assistant.turn_end",
        "session.idle",
      ]);
      expect(events[0]).toMatchObject({ type: "user.message", data: { content: "Hi", messageId }, parentId: null });
      for (let i = 1; i < events.length; i++) expect(events[i].parentId).toBe(events[i - 1].id);
      for (const e of events) {
        expect(typeof e.id).toBe("string");
        expect(Number.isNaN(Date.parse(e.timestamp))).toBe(false);
      }
      expect(events[1]).toMatchObject({ data: { turnId: "0", model: "gpt-test" } });
      expect(events[2]).toMatchObject({ ephemeral: true, data: { deltaContent: "thinking" } });
      expect(events[4]).toMatchObject({ data: { content: "Hello", originatingMessageId: messageId, model: "gpt-test" } });
      expect(events[5]).toMatchObject({ ephemeral: true, data: { inputTokens: 10, outputTokens: 2, model: "gpt-test" } });
      expect(events[7]).toMatchObject({ ephemeral: true, data: {} });
    });

    it("streams deltas in order with a shared messageId, then the full message", async () => {
      runtime.onPrompt(/.*/).streamReply(["Hel", "lo ", "world"]);
      const session = await newSession();
      const events = await run(session, "stream");
      const deltas = events.filter((e) => e.type === "assistant.message_delta");
      expect(deltas.map((d) => d.data.deltaContent)).toEqual(["Hel", "lo ", "world"]);
      const message = events.find((e) => e.type === "assistant.message")!;
      expect(message.data.content).toBe("Hello world");
      expect(new Set(deltas.map((d) => d.data.messageId))).toEqual(new Set([message.data.messageId]));
      expect(events.indexOf(message)).toBeGreaterThan(events.indexOf(deltas[2]));
    });

    it("emits a delta for plain replies when the session streams", async () => {
      runtime.onPrompt(/.*/).reply("whole");
      const session = await newSession({ streaming: true });
      const events = await run(session, "x");
      expect(events.filter((e) => e.type === "assistant.message_delta").map((e) => e.data.deltaContent)).toEqual(["whole"]);
    });

    it("emits custom events and computed replies", async () => {
      runtime
        .onPrompt(/.*/)
        .emit({ type: "session.info", data: { infoType: "note", message: "custom" } } as never)
        .replyWith((turn) => `echo: ${turn.prompt}`);
      const session = await newSession();
      const events = await run(session, "ping");
      expect(types(events)).toContain("session.info");
      expect(finalText(events)).toBe("echo: ping");
    });

    it(".fail emits session.error with errorType and ends the turn", async () => {
      runtime.onPrompt(/.*/).reply("partial").fail("model unavailable", "rate_limit").reply("never");
      const session = await newSession();
      const events = await run(session, "x");
      expect(types(events).slice(-3)).toEqual(["assistant.turn_end", "session.error", "session.idle"]);
      expect(events.at(-2)).toMatchObject({ data: { message: "model unavailable", errorType: "rate_limit" } });
      expect(finalText(events)).toBe("partial");
    });

    it("delivers events only to connected handlers", async () => {
      runtime.onPrompt(/.*/).reply("ok");
      const session = await newSession();
      const seen: string[] = [];
      const off = session.on((e) => seen.push(e.type));
      off();
      await run(session, "x");
      expect(seen).toEqual([]);
    });

    it("ignores throwing listeners", async () => {
      runtime.onPrompt(/.*/).reply("ok");
      const session = await newSession();
      session.on(() => {
        throw new Error("listener bug");
      });
      expect(finalText(await run(session, "x"))).toBe("ok");
    });

    it("runs queued turns serially", async () => {
      runtime.onPrompt("a").delay(20).reply("A");
      runtime.onPrompt("b").reply("B");
      const session = await newSession();
      const messages: string[] = [];
      let idles = 0;
      const done = new Promise<void>((resolve) =>
        session.on((e) => {
          if (e.type === "assistant.message") messages.push(e.data.content);
          if (e.type === "session.idle" && ++idles === 2) resolve();
        }),
      );
      await session.send({ prompt: "a" });
      await session.send({ prompt: "b" });
      await done;
      expect(messages).toEqual(["A", "B"]);
    });
  });

  describe("tool calls", () => {
    it("asks permission, invokes the handler, and records the call", async () => {
      const invocations: unknown[] = [];
      runtime.onPrompt(/order 42/).callTool("lookupOrder", { id: "42" }).thenReply("Order 42 shipped.");
      const session = await newSession({
        tools: [
          lookupOrderTool({
            handler: (args, invocation) => {
              invocations.push(invocation);
              return { id: args.id, status: "shipped" };
            },
          }),
        ],
      });
      const events = await run(session, "Where is order 42?");
      expect(types(events)).toEqual([
        "user.message",
        "assistant.turn_start",
        "assistant.message",
        "tool.execution_start",
        "permission.requested",
        "permission.completed",
        "tool.execution_complete",
        "assistant.turn_end",
        "assistant.turn_start",
        "assistant.message",
        "assistant.turn_end",
        "session.idle",
      ]);
      const request = events.find((e) => e.type === "assistant.message")!;
      const toolCallId = request.data.toolRequests![0].toolCallId;
      expect(request.data).toMatchObject({ content: "", toolRequests: [{ name: "lookupOrder", arguments: { id: "42" }, type: "function" }] });
      expect(events.find((e) => e.type === "tool.execution_start")!.data).toMatchObject({
        toolCallId,
        toolName: "lookupOrder",
        arguments: { id: "42" },
      });
      const permission = events.find((e) => e.type === "permission.requested")!;
      expect(permission.data.permissionRequest).toEqual({
        kind: "custom-tool",
        toolCallId,
        toolName: "lookupOrder",
        toolDescription: "Looks up an order",
        args: { id: "42" },
      });
      expect(events.find((e) => e.type === "permission.completed")!.data).toMatchObject({
        requestId: permission.data.requestId,
        toolCallId,
        result: { kind: "approved" },
      });
      expect(events.find((e) => e.type === "tool.execution_complete")!.data).toMatchObject({
        toolCallId,
        success: true,
        result: { content: JSON.stringify({ id: "42", status: "shipped" }) },
      });
      expect(finalText(events)).toBe("Order 42 shipped.");
      expect(invocations).toHaveLength(1);
      expect(invocations[0]).toMatchObject({ sessionId: session.sessionId, toolCallId, toolName: "lookupOrder", arguments: { id: "42" } });
      expect((invocations[0] as { signal: AbortSignal }).signal).toBeInstanceOf(AbortSignal);
      expect(runtime.toolCalls).toEqual([
        { sessionId: session.sessionId, toolCallId, name: "lookupOrder", args: { id: "42" }, result: { id: "42", status: "shipped" }, approved: true },
      ]);
      expect(runtime.permissionRequests).toHaveLength(1);
      expect(runtime.permissionRequests[0].result).toEqual({ kind: "approve-once" });
    });

    it("groups consecutive tool calls in one assistant message and exposes results to replyWith", async () => {
      runtime
        .onPrompt(/.*/)
        .callTool("lookupOrder", { id: "1" })
        .callTool("lookupOrder", { id: "2" })
        .replyWith((turn) => `found ${turn.toolCalls.length}, last ${JSON.stringify(turn.toolResult("lookupOrder"))}`);
      const session = await newSession({ tools: [lookupOrderTool()] });
      const events = await run(session, "x");
      const requests = events.filter((e) => e.type === "assistant.message" && e.data.toolRequests?.length);
      expect(requests).toHaveLength(1);
      expect(events.filter((e) => e.type === "tool.execution_complete")).toHaveLength(2);
      expect(finalText(events)).toBe('found 2, last {"id":"2","status":"shipped"}');
    });

    it("denies when the permission handler rejects", async () => {
      let called = false;
      runtime.onPrompt(/.*/).callTool("lookupOrder", { id: "1" }).reply("done");
      const session = await newSession({
        onPermissionRequest: reject,
        tools: [lookupOrderTool({ handler: () => (called = true) })],
      });
      const events = await run(session, "x");
      expect(called).toBe(false);
      expect(events.find((e) => e.type === "permission.completed")!.data.result).toEqual({
        kind: "denied-interactively-by-user",
        feedback: "nope",
      });
      const complete = events.find((e) => e.type === "tool.execution_complete")!;
      expect(complete.data.success).toBe(false);
      expect(complete.data.error?.message).toMatch(/denied/i);
      expect(runtime.toolCalls[0]).toMatchObject({ approved: false, error: expect.stringMatching(/denied/i) });
      expect(finalText(events)).toBe("done");
    });

    it("denies when there is no permission handler", async () => {
      runtime.onPrompt(/.*/).callTool("lookupOrder", { id: "1" }).reply("done");
      const session = await client.createSession({ tools: [lookupOrderTool()] } as SessionConfig);
      const events = await run(session, "x");
      expect(events.find((e) => e.type === "tool.execution_complete")!.data.success).toBe(false);
      expect(runtime.permissionRequests[0].result).toEqual({ kind: "user-not-available" });
    });

    it("treats a throwing permission handler as denial", async () => {
      runtime.onPrompt(/.*/).callTool("lookupOrder", { id: "1" }).reply("done");
      const session = await newSession({
        onPermissionRequest: () => {
          throw new Error("policy crashed");
        },
        tools: [lookupOrderTool()],
      });
      const events = await run(session, "x");
      expect(events.find((e) => e.type === "tool.execution_complete")!.data.success).toBe(false);
    });

    it("skips the permission request for skipPermission tools", async () => {
      runtime.onPrompt(/.*/).callTool("lookupOrder", { id: "7" }).reply("done");
      const session = await newSession({ onPermissionRequest: reject, tools: [lookupOrderTool({ skipPermission: true })] });
      const events = await run(session, "x");
      expect(types(events)).not.toContain("permission.requested");
      expect(events.find((e) => e.type === "tool.execution_complete")!.data.success).toBe(true);
      expect(runtime.permissionRequests).toEqual([]);
      expect(runtime.toolCalls[0].approved).toBe(true);
    });

    it("fails the call when the tool is not registered", async () => {
      runtime.onPrompt(/.*/).callTool("missing", {}).reply("done");
      const session = await newSession({ tools: [lookupOrderTool()] });
      const events = await run(session, "x");
      const complete = events.find((e) => e.type === "tool.execution_complete")!;
      expect(complete.data.success).toBe(false);
      expect(complete.data.error?.message).toContain('"missing"');
      expect(complete.data.error?.message).toContain("lookupOrder");
      expect(types(events)).not.toContain("permission.requested");
      expect(runtime.toolCalls[0]).toMatchObject({ name: "missing", approved: false });
      expect(finalText(events)).toBe("done");
    });

    it("fails the call when the handler throws and continues the turn", async () => {
      runtime.onPrompt(/.*/).callTool("lookupOrder", { id: "1" }).reply("recovered");
      const session = await newSession({
        tools: [
          lookupOrderTool({
            handler: () => {
              throw new Error("db down");
            },
          }),
        ],
      });
      const events = await run(session, "x");
      expect(events.find((e) => e.type === "tool.execution_complete")!.data).toMatchObject({
        success: false,
        error: { message: "db down" },
      });
      expect(runtime.toolCalls[0]).toMatchObject({ approved: true, error: "db down" });
      expect(finalText(events)).toBe("recovered");
    });

    it("passes ToolResultObject results through and reports failures", async () => {
      runtime.onPrompt(/.*/).callTool("lookupOrder", { id: "1" }).reply("done");
      const session = await newSession({
        tools: [lookupOrderTool({ handler: () => ({ textResultForLlm: "bad id", resultType: "failure", error: "bad id" }) })],
      });
      const events = await run(session, "x");
      expect(events.find((e) => e.type === "tool.execution_complete")!.data).toMatchObject({
        success: false,
        result: { content: "bad id" },
        error: { message: "bad id" },
      });
    });

    it("invokes pre/post tool hooks; preToolUse deny blocks the call", async () => {
      const seen: string[] = [];
      runtime.onPrompt("allow").callTool("lookupOrder", { id: "1" }).reply("ok");
      runtime.onPrompt("deny").callTool("lookupOrder", { id: "2" }).reply("ok");
      const session = await newSession({
        onPermissionRequest: reject,
        tools: [lookupOrderTool({ handler: (args) => `order ${args.id}` })],
        hooks: {
          onPreToolUse: (input) => {
            seen.push(`pre:${input.toolName}:${JSON.stringify(input.toolArgs)}`);
            return (input.toolArgs as { id: string }).id === "2"
              ? { permissionDecision: "deny", permissionDecisionReason: "blocked id" }
              : { permissionDecision: "allow", modifiedArgs: { id: "1b" } };
          },
          onPostToolUse: (input) => {
            seen.push(`post:${input.toolResult.textResultForLlm}`);
            return { modifiedResult: { textResultForLlm: "redacted", resultType: "success" } };
          },
          onPostToolUseFailure: (input) => {
            seen.push(`failure:${input.error}`);
          },
        },
      });
      const allowed = await run(session, "allow");
      expect(allowed.find((e) => e.type === "tool.execution_complete")!.data).toMatchObject({ success: true, result: { content: "redacted" } });
      expect(types(allowed)).not.toContain("permission.requested");
      const denied = await run(session, "deny");
      const complete = denied.find((e) => e.type === "tool.execution_complete")!;
      expect(complete.data.success).toBe(false);
      expect(complete.data.error?.message).toContain("blocked id");
      expect(seen).toEqual([
        'pre:lookupOrder:{"id":"1"}',
        "post:order 1b",
        'pre:lookupOrder:{"id":"2"}',
        expect.stringMatching(/^failure:.*blocked id/),
      ]);
      expect(runtime.toolCalls.map((c) => [c.args, c.approved])).toEqual([
        [{ id: "1b" }, true],
        [{ id: "2" }, false],
      ]);
    });

    it("applies onUserPromptSubmitted rewrites before matching", async () => {
      runtime.onPrompt("rewritten").reply("matched rewrite");
      const session = await newSession({ hooks: { onUserPromptSubmitted: () => ({ modifiedPrompt: "rewritten" }) } });
      const events = await run(session, "original");
      expect(events[0]).toMatchObject({ data: { content: "original", transformedContent: "rewritten" } });
      expect(finalText(events)).toBe("matched rewrite");
      expect(runtime.prompts).toEqual(["original"]);
    });
  });

  describe("sendAndWait", () => {
    const Answer = z.object({ term: z.string(), definition: z.string() });

    it("parses the final message with the schema", async () => {
      runtime.onPrompt(/.*/).callTool("lookupOrder", { id: "1" }).replyJson({ term: "DI", definition: "Dependency injection" });
      const session = await newSession({ tools: [lookupOrderTool()] });
      await expect(session.sendAndWait({ prompt: "define DI" }, Answer)).resolves.toEqual({
        term: "DI",
        definition: "Dependency injection",
      });
    });

    it("propagates schema validation failures", async () => {
      runtime.onPrompt(/.*/).replyJson({ term: "DI" });
      const session = await newSession();
      await expect(session.sendAndWait({ prompt: "x" }, Answer)).rejects.toBeInstanceOf(z.ZodError);
    });

    it("rejects non-JSON content", async () => {
      runtime.onPrompt(/.*/).reply("not json");
      const session = await newSession();
      await expect(session.sendAndWait({ prompt: "x" }, Answer)).rejects.toThrow(SyntaxError);
    });

    it("rejects when there is no structured assistant response", async () => {
      runtime.onPrompt(/.*/).usage({ inputTokens: 1 });
      const session = await newSession();
      await expect(session.sendAndWait({ prompt: "x" }, Answer)).rejects.toThrow(
        "The requested run completed without a structured assistant response.",
      );
    });

    it("rejects with the session.error message", async () => {
      runtime.onPrompt(/.*/).fail("quota exceeded");
      const session = await newSession();
      await expect(session.sendAndWait({ prompt: "x" }, Answer)).rejects.toThrow("quota exceeded");
      await expect(session.sendAndWait({ prompt: "unmatched" }, Answer)).rejects.toThrow("quota exceeded");
    });

    it("rejects on no match", async () => {
      const session = await newSession();
      await expect(session.sendAndWait({ prompt: "anything" }, Answer)).rejects.toThrow(/no scripted reply matches prompt "anything"/);
    });

    it("times out like the SDK", async () => {
      runtime.onPrompt(/.*/).delay(1_000).replyJson({ term: "a", definition: "b" });
      const session = await newSession();
      await expect(session.sendAndWait({ prompt: "x" }, Answer, 20)).rejects.toThrow("Timeout after 20ms waiting for session.idle");
      await session.abort();
    });

    it("rejects when aborted", async () => {
      runtime.onPrompt(/.*/).delay(1_000).replyJson({ term: "a", definition: "b" });
      const session = await newSession();
      const pending = session.sendAndWait({ prompt: "x" }, Answer);
      await waitFor(session, "user.message");
      await session.abort();
      await expect(pending).rejects.toThrow("The requested run was aborted before a structured result was completed.");
    });
  });

  describe("abort", () => {
    it("interrupts a delay promptly and emits abort then idle(aborted)", async () => {
      runtime.onPrompt(/.*/).reply("before").delay(10_000).reply("after");
      const session = await newSession();
      const { events, idle } = collect(session);
      await session.send({ prompt: "x" });
      await waitFor(session, "assistant.message");
      const started = Date.now();
      await session.abort();
      await idle;
      expect(Date.now() - started).toBeLessThan(1_000);
      expect(types(events).slice(-2)).toEqual(["abort", "session.idle"]);
      expect(events.at(-2)).toMatchObject({ data: { reason: "user_initiated" } });
      expect(events.at(-1)).toMatchObject({ data: { aborted: true } });
      expect(events.filter((e) => e.type === "assistant.message").map((e) => e.data.content)).toEqual(["before"]);
      expect(runtime.abortCount).toBe(1);
    });

    it("aborts the tool invocation signal mid-tool", async () => {
      let toolSignal: AbortSignal | undefined;
      runtime.onPrompt(/.*/).callTool("slow", {}).reply("never");
      const session = await newSession({
        tools: [
          {
            name: "slow",
            skipPermission: true,
            handler: (_args, invocation) => {
              toolSignal = invocation.signal;
              return new Promise(() => {});
            },
          },
        ],
      });
      const { events, idle } = collect(session);
      await session.send({ prompt: "x" });
      await waitFor(session, "tool.execution_start");
      await new Promise((r) => setTimeout(r, 5));
      await session.abort();
      await idle;
      expect(toolSignal?.aborted).toBe(true);
      expect(types(events).slice(-3)).toEqual(["tool.execution_complete", "abort", "session.idle"]);
      expect(events.at(-3)).toMatchObject({ data: { success: false, error: { code: "aborted" } } });
      expect(finalText(events.filter((e) => !(e.type === "assistant.message" && e.data.toolRequests)))).toBeUndefined();
      expect(runtime.toolCalls[0]).toMatchObject({ name: "slow", approved: true, error: "Tool execution aborted" });
    });

    it("is a no-op when idle but still counted", async () => {
      const session = await newSession();
      await session.abort();
      expect(runtime.abortCount).toBe(1);
      expect(await session.getEvents()).toEqual([]);
    });
  });

  describe("sessions", () => {
    it("records the create config and uses the provided sessionId", async () => {
      const config = { sessionId: "agent-u1-c1", model: "m1", systemMessage: { mode: "append" as const, content: "be nice" }, availableTools: ["custom:*"] } satisfies Partial<SessionConfig>;
      const session = await newSession(config);
      expect(session.sessionId).toBe("agent-u1-c1");
      expect(runtime.createdSessions).toHaveLength(1);
      expect(runtime.createdSessions[0]).toMatchObject(config);
      expect(runtime.sessionConfig("agent-u1-c1")).toMatchObject(config);
    });

    it("generates a sessionId when none is given", async () => {
      const session = await newSession();
      expect(session.sessionId).toMatch(/[0-9a-f-]{36}/);
    });

    it("resumes known sessions with history and new config; unknown throws", async () => {
      runtime.onPrompt(/.*/).reply("ok");
      const first = await newSession({ sessionId: "s1" });
      await run(first, "one");
      await first.disconnect();

      await expect(client.resumeSession("nope", { onPermissionRequest: approve })).rejects.toThrow("Session not found: nope");

      const resumed = await client.resumeSession("s1", { onPermissionRequest: approve, model: "m2" });
      expect(runtime.resumedSessions).toEqual([{ sessionId: "s1", config: expect.objectContaining({ model: "m2" }) }]);
      await run(resumed, "two");
      const history = await resumed.getEvents();
      expect(history.filter((e) => e.type === "user.message").map((e) => e.data.content)).toEqual(["one", "two"]);
      expect(history.filter((e) => e.type === "assistant.turn_start").at(-1)).toMatchObject({ data: { model: "m2" } });
      expect(runtime.events("s1")).toEqual(history);
    });

    it("tracks metadata and deletes sessions", async () => {
      runtime.onPrompt(/.*/).reply("ok");
      const session = await newSession({ sessionId: "s2" });
      await run(session, "summary prompt");
      expect(await client.getSessionMetadata!("s2")).toMatchObject({ sessionId: "s2", summary: "summary prompt", isRemote: false });
      await client.deleteSession("s2");
      expect(runtime.deletedSessions).toEqual(["s2"]);
      expect(await client.getSessionMetadata!("s2")).toBeUndefined();
      await expect(client.resumeSession("s2", {})).rejects.toThrow("Session not found: s2");
      await expect(session.send({ prompt: "x" })).rejects.toThrow("Session is disconnected");
    });

    it("disconnect unsubscribes handlers and blocks sends", async () => {
      runtime.onPrompt(/.*/).reply("ok");
      const session = (await newSession()) as TestSession;
      const seen: string[] = [];
      session.on((e) => seen.push(e.type));
      await session.disconnect();
      await expect(session.send({ prompt: "x" })).rejects.toThrow("Session is disconnected");
      expect(seen).toEqual([]);
    });
  });

  describe("recordings and reset", () => {
    it("records prompts and resets everything", async () => {
      runtime.onPrompt(/.*/).reply("ok");
      const session = await newSession();
      await run(session, "a");
      await run(session, "b");
      expect(runtime.prompts).toEqual(["a", "b"]);
      expect(runtime.receivedPrompts.map((p) => p.sessionId)).toEqual([session.sessionId, session.sessionId]);

      runtime.reset();
      expect(runtime.prompts).toEqual([]);
      expect(runtime.createdSessions).toEqual([]);
      expect(runtime.sessionIds).toEqual([]);
      expect(runtime.started).toBe(false);
      const fresh = await newSession();
      const events = await run(fresh, "a");
      expect(types(events)).toContain("session.error");
    });
  });
});
