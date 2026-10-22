import { afterEach, describe, expect, it } from "vitest";
import { z } from "zod";
import { defineTool } from "@github/copilot-sdk";
import {
  CopilotAbortedError,
  CopilotAdmissionRejectedError,
  CopilotOutputValidationError,
  CopilotShuttingDownError,
  CopilotTimeoutError,
  defineAgent,
  defineCopilotTool,
  deriveSessionId,
  getCopilotContext,
  permissions,
  runWithCopilotContext,
  type CopilotAgentEvent,
  type CopilotContext,
} from "../src/index.js";
import { createTestCopilot, type CreateTestCopilotOptions, type TestCopilot } from "../src/testing/index.js";

const assistant = defineAgent({ name: "assistant", instructions: "Be helpful." });

let current: TestCopilot | undefined;
function setup(options: CreateTestCopilotOptions = {}): TestCopilot {
  current = createTestCopilot(options);
  return current;
}
afterEach(async () => {
  await current?.stop({ gracePeriodMs: 0 });
  current = undefined;
});

async function collect(events: AsyncIterable<CopilotAgentEvent>): Promise<CopilotAgentEvent[]> {
  const out: CopilotAgentEvent[] = [];
  for await (const event of events) out.push(event);
  return out;
}

describe("createTestCopilot", () => {
  it("wires a lazy copilot to the scripted runtime with test defaults", async () => {
    const { copilot, runtime } = setup({ script: (rt) => rt.onPrompt(/.*/).reply("Hello") });
    expect(copilot.state).toBe("not-started");
    await expect(copilot.agent(assistant).send({ prompt: "Hi" })).resolves.toBe("Hello");
    expect(copilot.state).toBe("running");
    expect(runtime.prompts).toEqual(["Hi"]);
    expect(runtime.clientOptions[0]).toMatchObject({ mode: "empty" });
    expect(runtime.clientOptions[0]!.baseDirectory).not.toMatch(/\.copilot$/);
  });

  it("is async-disposable and stops the runtime", async () => {
    let runtimeRef;
    {
      await using test = createTestCopilot({ script: (rt) => rt.onPrompt(/.*/).reply("ok") });
      runtimeRef = test.runtime;
      await test.copilot.agent(assistant).send({ prompt: "x" });
    }
    expect(runtimeRef.stopped).toBe(true);
  });
});

describe("integration: core over the scripted runtime", () => {
  it("sends a plain prompt and cleans up the ephemeral session", async () => {
    const { copilot, runtime } = setup();
    runtime.onPrompt("capital").reply("Paris");
    await expect(copilot.agent(assistant).send({ prompt: "capital of France?" })).resolves.toBe("Paris");

    const config = runtime.createdSessions[0]!;
    expect(config.systemMessage).toEqual({ mode: "append", content: "Be helpful." });
    expect(config.availableTools).toEqual(["custom:*", "mcp:*"]);
    expect(config.excludedTools).toEqual(["builtin:*"]);
    expect(typeof config.onPermissionRequest).toBe("function");
    expect(runtime.deletedSessions).toEqual([config.sessionId]);
  });

  it("streams text deltas and a final done event", async () => {
    const { copilot, runtime } = setup();
    runtime.onPrompt(/.*/).reasoning("thinking").streamReply(["Hel", "lo"]).usage({ inputTokens: 3, outputTokens: 2 });

    const events = await collect(copilot.agent(assistant).stream({ prompt: "greet" }));
    const types = events.map((e) => e.type);
    expect(types[0]).toBe("turn-start");
    expect(types.at(-1)).toBe("done");
    expect(events.filter((e) => e.type === "text-delta").map((e) => (e as { delta: string }).delta)).toEqual(["Hel", "lo"]);
    expect(events).toContainEqual({ type: "reasoning-delta", delta: "thinking" });
    expect(events).toContainEqual(expect.objectContaining({ type: "usage", inputTokens: 3, outputTokens: 2 }));
    expect(events.at(-1)).toMatchObject({ type: "done", text: "Hello" });
    expect(runtime.createdSessions[0]!.streaming).toBe(true);
  });

  describe("structured output", () => {
    const Answer = z.object({ city: z.string(), population: z.number() });

    it("returns a validated value", async () => {
      const { copilot, runtime } = setup();
      runtime.onPrompt(/.*/).replyJson({ city: "Paris", population: 2_100_000 });
      await expect(copilot.agent(assistant).send({ prompt: "biggest city", schema: Answer })).resolves.toEqual({
        city: "Paris",
        population: 2_100_000,
      });
    });

    it("wraps non-object schemas", async () => {
      const { copilot, runtime } = setup();
      runtime.onPrompt(/.*/).replyJson({ value: ["a", "b"] });
      await expect(copilot.agent(assistant).send({ prompt: "letters", schema: z.array(z.string()) })).resolves.toEqual(["a", "b"]);
    });

    it("rejects invalid output with CopilotOutputValidationError", async () => {
      const { copilot, runtime } = setup();
      runtime.onPrompt("wrong").replyJson({ city: 42 }).onPrompt("prose").reply("not json");
      await expect(copilot.agent(assistant).send({ prompt: "wrong", schema: Answer })).rejects.toBeInstanceOf(
        CopilotOutputValidationError,
      );
      await expect(copilot.agent(assistant).send({ prompt: "prose", schema: Answer })).rejects.toBeInstanceOf(
        CopilotOutputValidationError,
      );
    });
  });

  describe("custom tools", () => {
    const seen: { input: unknown; context: CopilotContext | undefined }[] = [];
    const lookupOrder = defineCopilotTool({
      name: "lookupOrder",
      description: "Look up an order",
      input: z.object({ id: z.string() }),
      execute: async ({ id }) => {
        seen.push({ input: { id }, context: getCopilotContext() });
        return { id, status: "shipped" };
      },
    });
    afterEach(() => {
      seen.length = 0;
    });

    function scriptOrder(test: TestCopilot) {
      test.runtime
        .onPrompt(/order 42/)
        .callTool("lookupOrder", { id: "42" })
        .replyWith((turn) => {
          const call = turn.toolCalls[0]!;
          // The model sees the tool output as text (core serializes objects to JSON).
          return call.approved ? `Order ${JSON.parse(call.result as string).status}.` : `Denied: ${call.error}`;
        });
    }

    it("executes an allowed tool with the caller's context and feeds the result back", async () => {
      const test = setup();
      scriptOrder(test);
      const support = defineAgent({ name: "support", tools: [lookupOrder], permissions: permissions.allowCustomTools() });

      const answer = await runWithCopilotContext({ principal: "ambient-user", attributes: { tenant: "acme" } }, () =>
        test.copilot.agent(support).send({
          prompt: "Where is order 42?",
          conversationId: "c1",
          context: { attributes: { requestId: "r-1" } },
        }),
      );

      expect(answer).toBe("Order shipped.");
      expect(test.runtime.toolCalls).toMatchObject([{ name: "lookupOrder", args: { id: "42" }, approved: true }]);
      expect(seen).toHaveLength(1);
      expect(seen[0]!.input).toEqual({ id: "42" });
      expect(seen[0]!.context).toMatchObject({
        principal: "ambient-user",
        conversationId: "c1",
        agentName: "support",
        sessionId: deriveSessionId("support", "ambient-user", "c1"),
        attributes: { tenant: "acme", requestId: "r-1" },
      });
      expect(seen[0]!.context!.signal).toBeInstanceOf(AbortSignal);
    });

    it("binds the caller's context into SDK tools, hooks, and permission policies", async () => {
      const test = setup();
      const observed: Record<string, string | undefined> = {};
      test.runtime.onPrompt(/.*/).callTool("sdkTool", {}).reply("done");
      const sdkTool = defineTool("sdkTool", {
        description: "SDK tool",
        parameters: z.object({}),
        handler: () => {
          observed.tool = getCopilotContext()?.principal;
          return "ok";
        },
      });
      const agent = defineAgent({
        name: "binder",
        tools: [sdkTool],
        permissions: (_request, ctx) => {
          observed.policy = ctx.context.principal;
          observed.policyAls = getCopilotContext()?.principal;
          return "approve";
        },
        hooks: {
          onPreToolUse: () => {
            observed.hook = getCopilotContext()?.principal;
            return undefined;
          },
        },
      });
      await expect(test.copilot.agent(agent).send({ prompt: "go", context: { principal: "alice" } })).resolves.toBe("done");
      expect(observed).toEqual({ tool: "alice", policy: "alice", policyAls: "alice", hook: "alice" });
    });

    it("denies tools under the default denyAll policy", async () => {
      const test = setup();
      scriptOrder(test);
      const support = defineAgent({ name: "support", tools: [lookupOrder], permissions: permissions.denyAll() });

      const answer = await test.copilot.agent(support).send({ prompt: "Where is order 42?" });
      expect(answer).toMatch(/^Denied: Permission denied/);
      expect(seen).toHaveLength(0);
      expect(test.runtime.permissionRequests).toHaveLength(1);
      expect(test.runtime.permissionRequests[0]!.result.kind).not.toMatch(/^approve/);
    });

    it("does not run tools hidden by availableTools", async () => {
      const test = setup();
      scriptOrder(test);
      const other = defineCopilotTool({ name: "other", description: "Other", execute: () => "x" });
      const support = defineAgent({
        name: "support",
        tools: [lookupOrder, other],
        availableTools: ["custom:other"],
        permissions: permissions.allowCustomTools(),
      });
      const answer = await test.copilot.agent(support).send({ prompt: "Where is order 42?" });
      expect(answer).toMatch(/not available/);
      expect(seen).toHaveLength(0);
      expect(test.runtime.createdSessions[0]).toMatchObject({ availableTools: ["custom:other"], excludedTools: ["builtin:*"] });
    });

    it("routes ask-user permissions to the stream consumer", async () => {
      const test = setup();
      scriptOrder(test);
      const support = defineAgent({ name: "support", tools: [lookupOrder], permissions: permissions.askUser() });

      const events: CopilotAgentEvent[] = [];
      for await (const event of test.copilot.agent(support).stream({ prompt: "Where is order 42?" })) {
        events.push(event);
        if (event.type === "permission-request") {
          expect(event.request).toMatchObject({ kind: "custom-tool", toolName: "lookupOrder" });
          event.respond({ approved: true });
        }
      }
      expect(events).toContainEqual(expect.objectContaining({ type: "permission-resolved", approved: true }));
      expect(events).toContainEqual(expect.objectContaining({ type: "tool-end", toolName: "lookupOrder", success: true }));
      expect(events.at(-1)).toMatchObject({ type: "done", text: "Order shipped." });
      expect(seen).toHaveLength(1);
    });

    it("denies ask-user permissions the stream consumer rejects", async () => {
      const test = setup();
      scriptOrder(test);
      const support = defineAgent({ name: "support", tools: [lookupOrder], permissions: permissions.askUser() });

      const events: CopilotAgentEvent[] = [];
      for await (const event of test.copilot.agent(support).stream({ prompt: "Where is order 42?" })) {
        events.push(event);
        if (event.type === "permission-request") event.respond({ approved: false, feedback: "no" });
      }
      expect(events).toContainEqual(expect.objectContaining({ type: "permission-resolved", approved: false }));
      expect(events.at(-1)).toMatchObject({ type: "done", text: expect.stringMatching(/^Denied/) });
      expect(seen).toHaveLength(0);
    });

    it("resolves ask-user permissions through onPermissionRequest in send()", async () => {
      const test = setup();
      scriptOrder(test);
      const support = defineAgent({ name: "support", tools: [lookupOrder], permissions: permissions.askUser() });
      await expect(
        test.copilot.agent(support).send({ prompt: "order 42", onPermissionRequest: () => ({ approved: true }) }),
      ).resolves.toBe("Order shipped.");
    });
  });

  describe("conversations", () => {
    it("resumes the same session for the same conversation and principal", async () => {
      const { copilot, runtime } = setup();
      runtime.onPrompt(/.*/).replyWith((turn) => `echo: ${turn.prompt}`);
      const agent = copilot.agent(assistant);

      await agent.send({ prompt: "one", conversationId: "conv", context: { principal: "alice" } });
      await agent.send({ prompt: "two", conversationId: "conv", context: { principal: "alice" } });
      await agent.send({ prompt: "three", conversationId: "conv", context: { principal: "bob" } });

      const alice = deriveSessionId("assistant", "alice", "conv");
      const bob = deriveSessionId("assistant", "bob", "conv");
      expect(alice).not.toBe(bob);
      expect(runtime.createdSessions.map((c) => c.sessionId)).toEqual([alice, bob]);
      expect(runtime.resumedSessions.map((r) => r.sessionId)).toEqual([alice]);
      expect(runtime.receivedPrompts.map((p) => [p.sessionId, p.prompt])).toEqual([
        [alice, "one"],
        [alice, "two"],
        [bob, "three"],
      ]);
      expect(runtime.deletedSessions).toEqual([]);
    });

    it("supports bound conversations and deletion", async () => {
      const { copilot, runtime } = setup();
      runtime.onPrompt(/.*/).reply("ok");
      const agent = copilot.agent(assistant);
      const bound = agent.conversation("c2", { mode: "create-or-resume", principal: "carol" });
      await expect(bound.send("hi")).resolves.toBe("ok");
      await agent.deleteConversation("c2", { principal: "carol" });
      expect(runtime.deletedSessions).toEqual([deriveSessionId("assistant", "carol", "c2")]);
    });
  });

  describe("limits, cancellation, and shutdown", () => {
    it("rejects turns beyond maxConcurrentTurns + maxQueuedTurns", async () => {
      const { copilot, runtime } = setup({ limits: { maxConcurrentTurns: 1, maxQueuedTurns: 1 } });
      runtime.onPrompt("slow").delay(50).reply("slow done").onPrompt(/.*/).reply("fast");
      const agent = copilot.agent(assistant);

      const first = agent.send({ prompt: "slow" });
      const second = agent.send({ prompt: "queued" });
      await expect(agent.send({ prompt: "rejected" })).rejects.toBeInstanceOf(CopilotAdmissionRejectedError);
      await expect(first).resolves.toBe("slow done");
      await expect(second).resolves.toBe("fast");
      expect(copilot.capacity()).toMatchObject({ activeTurns: 0, queuedTurns: 0 });
    });

    it("aborts a turn through its AbortSignal", async () => {
      const { copilot, runtime } = setup();
      runtime.onPrompt(/.*/).delay(10_000).reply("never");
      const controller = new AbortController();
      const pending = copilot.agent(assistant).send({ prompt: "long", signal: controller.signal });
      await waitFor(() => runtime.prompts.length === 1);
      controller.abort();
      await expect(pending).rejects.toBeInstanceOf(CopilotAbortedError);
      expect(runtime.abortCount).toBe(1);
      expect(copilot.capacity().activeTurns).toBe(0);
    });

    it("times out a slow turn", async () => {
      const { copilot, runtime } = setup();
      runtime.onPrompt(/.*/).delay(10_000).reply("never");
      await expect(copilot.agent(assistant).send({ prompt: "slow", timeout: 30 })).rejects.toBeInstanceOf(CopilotTimeoutError);
      expect(runtime.abortCount).toBe(1);
    });

    it("drains in-flight turns on graceful stop and rejects new ones", async () => {
      const test = setup();
      test.runtime.onPrompt(/.*/).delay(20).reply("finished");
      const pending = test.copilot.agent(assistant).send({ prompt: "work" });
      await waitFor(() => test.runtime.prompts.length === 1);

      await test.stop({ gracePeriodMs: 5_000 });
      await expect(pending).resolves.toBe("finished");
      expect(test.copilot.state).toBe("stopped");
      expect(test.runtime.stopped).toBe(true);
      await expect(test.copilot.agent(assistant).send({ prompt: "late" })).rejects.toBeInstanceOf(CopilotShuttingDownError);
    });

    it("aborts turns that outlive the grace period", async () => {
      const test = setup();
      test.runtime.onPrompt(/.*/).delay(10_000).reply("never");
      const pending = test.copilot.agent(assistant).send({ prompt: "work" });
      await waitFor(() => test.runtime.prompts.length === 1);
      await test.stop({ gracePeriodMs: 10 });
      await expect(pending).rejects.toBeInstanceOf(CopilotShuttingDownError);
      expect(test.copilot.state).toBe("stopped");
    });
  });
});

async function waitFor(condition: () => boolean, timeoutMs = 2_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline) throw new Error("waitFor timed out");
    await new Promise((resolve) => setTimeout(resolve, 1));
  }
}
