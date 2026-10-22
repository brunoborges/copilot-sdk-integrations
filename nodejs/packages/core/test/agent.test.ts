import { afterEach, describe, expect, it, vi } from "vitest";
import { z } from "zod";
import type { PermissionRequest, PermissionRequestResult } from "@github/copilot-sdk";
import { createCopilot } from "../src/client.js";
import { getCopilotContext, runWithCopilotContext } from "../src/context.js";
import { deriveSessionId } from "../src/conversation.js";
import {
  CopilotAbortedError,
  CopilotAdmissionRejectedError,
  CopilotConfigurationError,
  CopilotConversationBusyError,
  CopilotConversationExistsError,
  CopilotConversationNotFoundError,
  CopilotOutputValidationError,
  CopilotSessionError,
  CopilotTimeoutError,
} from "../src/errors.js";
import { permissions } from "../src/governance/index.js";
import { memoryStore } from "../src/stores/index.js";
import type { CopilotAgentDefinition, CopilotAgentEvent, CopilotContext, CopilotOptions } from "../src/types.js";
import { FakeClient, type FakeSession, type TurnScript, deferred, hangingScript, replyScript, silentLogger, tick } from "./fakes.js";

afterEach(() => vi.useRealTimers());

function setup(options: Partial<CopilotOptions> & { fake?: FakeClient } = {}) {
  const fake = options.fake ?? new FakeClient();
  const logger = silentLogger();
  const copilot = createCopilot({ logger, ...options, clientFactory: () => fake });
  return { fake, copilot, logger };
}

const shellRequest = { kind: "shell", fullCommandText: "rm -rf /", intention: "cleanup" } as unknown as PermissionRequest;
const toolRequest = (toolName: string, args: unknown = {}) =>
  ({ kind: "custom-tool", toolCallId: "tc1", toolName, args }) as unknown as PermissionRequest;

/** A turn that asks for `request` and replies with the resulting permission kind. */
function permissionScript(request: PermissionRequest, seen: PermissionRequestResult[] = []): TurnScript {
  return async (session, prompt) => {
    session.emit("user.message", { content: prompt });
    const result = await session.requestPermission(request);
    seen.push(result);
    session.emit("assistant.message", { messageId: "m", content: `permission:${result.kind}` });
    session.emit("session.idle", {});
  };
}

async function collect(iterable: AsyncIterable<CopilotAgentEvent>): Promise<CopilotAgentEvent[]> {
  const events: CopilotAgentEvent[] = [];
  for await (const event of iterable) events.push(event);
  return events;
}

const bot: CopilotAgentDefinition = { name: "bot", instructions: "Be terse." };

describe("send()", () => {
  it("runs an ephemeral turn: create, send, disconnect, delete", async () => {
    const { fake, copilot } = setup();
    await expect(copilot.agent(bot).send({ prompt: "hello" })).resolves.toBe("echo: hello");
    const session = fake.lastSession;
    expect(session.sessionId).toMatch(/^copilot-bot-[0-9a-f-]{36}$/);
    expect(session.disconnect).toHaveBeenCalledTimes(1);
    expect(fake.deleteSession).toHaveBeenCalledWith(session.sessionId);
    expect(fake.persisted.size).toBe(0);
    expect(session.listeners.size).toBe(0);
  });

  it("uses a fresh session id for every ephemeral turn", async () => {
    const { fake, copilot } = setup();
    await copilot.agent(bot).send({ prompt: "a" });
    await copilot.agent(bot).send({ prompt: "b" });
    expect(fake.sessions[0]!.sessionId).not.toBe(fake.sessions[1]!.sessionId);
  });

  it("builds the session config from the definition", async () => {
    const { fake, copilot } = setup();
    const lookup = { name: "lookup", description: "Look up", execute: () => "x" };
    await copilot.agent({
      name: "cfg",
      instructions: "Be nice.",
      model: "gpt-x",
      reasoningEffort: "high",
      tools: [lookup],
      infiniteSessions: true,
      mcpServers: { gh: { type: "http", url: "http://mcp", tools: ["*"] } },
    }).send({ prompt: "x", tools: [{ name: "extra", description: "Extra", execute: () => "y" }] });
    const config = fake.created[0]!;
    expect(config.model).toBe("gpt-x");
    expect(config.reasoningEffort).toBe("high");
    expect(config.systemMessage).toEqual({ mode: "append", content: "Be nice." });
    expect(config.tools?.map((t) => t.name).sort()).toEqual(["extra", "lookup"]);
    expect(config.infiniteSessions).toEqual({ enabled: true });
    expect(config.mcpServers).toHaveProperty("gh");
    expect(config.availableTools).toEqual(["custom:*", "mcp:*"]);
    expect(config.excludedTools).toEqual(["builtin:*"]);
    expect(config.streaming).toBeUndefined();
    expect(config.onPermissionRequest).toBeTypeOf("function");
  });

  it("keeps explicit availableTools and enables built-ins when asked", async () => {
    const { fake, copilot } = setup();
    await copilot.agent({ name: "a1", availableTools: ["custom:*", "builtin:view"], builtInTools: true }).send({ prompt: "x" });
    await copilot.agent({ name: "a2", builtInTools: true }).send({ prompt: "x" });
    expect(fake.created[0]!.availableTools).toEqual(["custom:*", "builtin:view"]);
    expect(fake.created[1]!.availableTools).toEqual(["builtin:*", "custom:*", "mcp:*"]);
    expect(fake.created[1]!.excludedTools).toBeUndefined();
  });

  it("passes object instructions through", async () => {
    const { fake, copilot } = setup();
    await copilot.agent({ name: "r", instructions: { mode: "replace", content: "Only this." } }).send({ prompt: "x" });
    expect(fake.created[0]!.systemMessage).toEqual({ mode: "replace", content: "Only this." });
  });

  it("returns the last top-level assistant message, ignoring sub-agent and blank messages", async () => {
    const { copilot } = setup({
      fake: new FakeClient({
        script: async (session, prompt) => {
          session.emit("user.message", { content: prompt });
          session.emit("assistant.message", { messageId: "1", content: "first" });
          session.emit("assistant.message", { messageId: "2", content: "", toolRequests: [] });
          session.emit("assistant.message", { messageId: "3", content: "from sub-agent" }, { agentId: "sub" });
          session.emit("assistant.message", { messageId: "4", content: "nested", parentToolCallId: "t" });
          session.emit("session.idle", {}, { agentId: "sub" });
          session.emit("assistant.message", { messageId: "5", content: "final" });
          session.emit("session.idle", {});
        },
      }),
    });
    await expect(copilot.agent(bot).send({ prompt: "x" })).resolves.toBe("final");
  });

  it("ignores an idle that arrives before the turn started", async () => {
    const fake = new FakeClient();
    const { copilot } = setup({ fake });
    fake.script = async (session, prompt) => {
      await tick();
      session.emit("assistant.message", { messageId: "1", content: "answer" });
      session.emit("session.idle", {});
      void prompt;
    };
    const originalCreate = fake.createSession.bind(fake);
    fake.createSession = async (config) => {
      const session = (await originalCreate(config)) as FakeSession;
      const originalSend = session.send.bind(session);
      session.send = async (options) => {
        session.emit("session.idle", {}); // stale idle before the turn begins
        return originalSend(options);
      };
      return session;
    };
    await expect(copilot.agent(bot).send({ prompt: "x" })).resolves.toBe("answer");
  });

  it("rejects with CopilotSessionError on session.error and still cleans up", async () => {
    const fake = new FakeClient({
      script: async (session, prompt) => {
        session.emit("user.message", { content: prompt });
        session.emit("session.error", { errorType: "rate_limit", message: "Slow down" });
      },
    });
    const { copilot } = setup({ fake });
    const error = await copilot.agent(bot).send({ prompt: "x" }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(CopilotSessionError);
    expect((error as CopilotSessionError).errorType).toBe("rate_limit");
    expect(fake.lastSession.disconnect).toHaveBeenCalled();
    expect(fake.deleteSession).toHaveBeenCalled();
    expect(fake.lastSession.abort).not.toHaveBeenCalled();
  });

  it("validates arguments", async () => {
    const { copilot } = setup();
    const agent = copilot.agent(bot);
    await expect(agent.send({ prompt: "  " })).rejects.toBeInstanceOf(TypeError);
    await expect(agent.send({ prompt: "x", conversationId: "" })).rejects.toBeInstanceOf(TypeError);
    await expect(agent.send({ prompt: "x", timeout: -1 })).rejects.toBeInstanceOf(TypeError);
    expect(() => copilot.agent({ name: "bad name!" })).toThrow(CopilotConfigurationError);
  });

  it("applies configureSession last, and guards the session id and permission handler", async () => {
    const { fake, copilot } = setup();
    await copilot.agent({ name: "c1", configureSession: (config) => ({ ...config, model: "custom" }) }).send({ prompt: "x" });
    expect(fake.created[0]!.model).toBe("custom");
    await expect(
      copilot.agent({ name: "c2", configureSession: (config) => ({ ...config, sessionId: "other" }) }).send({ prompt: "x" }),
    ).rejects.toBeInstanceOf(CopilotConfigurationError);
    await expect(
      copilot
        .agent({ name: "c3", configureSession: (config) => ({ ...config, onPermissionRequest: undefined as never }) })
        .send({ prompt: "x" }),
    ).rejects.toBeInstanceOf(CopilotConfigurationError);
  });
});

describe("conversations", () => {
  it("derives a stable session id from agent, principal and conversation", async () => {
    const { fake, copilot } = setup();
    const agent = copilot.agent(bot);
    await agent.send({ prompt: "1", conversationId: "c1", context: { principal: "alice" } });
    await agent.send({ prompt: "2", conversationId: "c1", context: { principal: "alice" } });
    await runWithCopilotContext({ principal: "bob" }, () => agent.send({ prompt: "3", conversationId: "c1" }));
    const alice = deriveSessionId("bot", "alice", "c1");
    const bob = deriveSessionId("bot", "bob", "c1");
    expect(fake.sessions.map((s) => s.sessionId)).toEqual([alice, alice, bob]);
    expect(fake.sessions.map((s) => s.resumed)).toEqual([false, true, false]);
    expect(fake.deleteSession).not.toHaveBeenCalled();
    expect(fake.persisted.has(alice)).toBe(true);
    expect(fake.sessions.every((s) => s.disconnect.mock.calls.length === 1)).toBe(true);
  });

  it("does not pass create-only fields when resuming", async () => {
    const { fake, copilot } = setup();
    const agent = copilot.agent(bot);
    await agent.send({ prompt: "1", conversationId: "c" });
    await agent.send({ prompt: "2", conversationId: "c" });
    const resumed = fake.resumedConfigs[0]!.config as Record<string, unknown>;
    expect(resumed.sessionId).toBeUndefined();
    expect(resumed.onPermissionRequest).toBeTypeOf("function");
    expect(resumed.systemMessage).toEqual({ mode: "append", content: "Be terse." });
  });

  for (const metadata of [true, false]) {
    describe(metadata ? "with getSessionMetadata" : "without getSessionMetadata (resume probe)", () => {
      it("resume-only rejects unknown conversations, then resumes existing ones", async () => {
        const { fake, copilot } = setup({ fake: new FakeClient({ metadata }) });
        const agent = copilot.agent(bot);
        await expect(agent.conversation("c").send("hi")).rejects.toBeInstanceOf(CopilotConversationNotFoundError);
        expect(fake.created).toHaveLength(0);
        await agent.conversation("c", { mode: "create-only" }).send("first");
        await expect(agent.conversation("c").send("again")).resolves.toBe("echo: again");
        expect(fake.lastSession.resumed).toBe(true);
      });

      it("create-only rejects existing conversations", async () => {
        const { fake, copilot } = setup({ fake: new FakeClient({ metadata }) });
        const agent = copilot.agent(bot);
        await agent.send({ prompt: "x", conversationId: "c" });
        await expect(agent.conversation("c", { mode: "create-only" }).send("y")).rejects.toBeInstanceOf(
          CopilotConversationExistsError,
        );
        expect(fake.created).toHaveLength(1);
      });

      it("create-or-resume creates then resumes", async () => {
        const { fake, copilot } = setup({ fake: new FakeClient({ metadata }) });
        const conv = copilot.agent(bot).conversation("c", { mode: "create-or-resume" });
        await conv.send("1");
        await conv.send("2");
        expect(fake.sessions.map((s) => s.resumed)).toEqual([false, true]);
      });
    });
  }

  it("bound conversations use their explicit principal", async () => {
    const { fake, copilot } = setup();
    await runWithCopilotContext({ principal: "ambient" }, () =>
      copilot.agent(bot).conversation("c", { mode: "create-or-resume", principal: "explicit" }).send("x"),
    );
    expect(fake.lastSession.sessionId).toBe(deriveSessionId("bot", "explicit", "c"));
  });

  it("serializes turns of the same conversation and runs different conversations in parallel", async () => {
    const gates = new Map<string, ReturnType<typeof deferred<void>>>();
    const running = new Set<string>();
    let maxSameConversation = 0;
    let maxOverall = 0;
    const fake = new FakeClient({
      script: async (session, prompt) => {
        session.emit("user.message", { content: prompt });
        running.add(prompt);
        const sameConv = [...running].filter((p) => p[0] === prompt[0]).length;
        maxSameConversation = Math.max(maxSameConversation, sameConv);
        maxOverall = Math.max(maxOverall, running.size);
        const gate = deferred<void>();
        gates.set(prompt, gate);
        await gate.promise;
        running.delete(prompt);
        session.emit("assistant.message", { messageId: "m", content: prompt });
        session.emit("session.idle", {});
      },
    });
    const { copilot } = setup({ fake });
    const agent = copilot.agent(bot);
    const a1 = agent.send({ prompt: "a1", conversationId: "A" });
    const a2 = agent.send({ prompt: "a2", conversationId: "A" });
    const b1 = agent.send({ prompt: "b1", conversationId: "B" });
    await vi.waitFor(() => expect(gates.size).toBe(2));
    expect([...gates.keys()].sort()).toEqual(["a1", "b1"]);
    gates.get("a1")!.resolve();
    gates.get("b1")!.resolve();
    await vi.waitFor(() => expect(gates.has("a2")).toBe(true));
    gates.get("a2")!.resolve();
    await expect(Promise.all([a1, a2, b1])).resolves.toEqual(["a1", "a2", "b1"]);
    expect(maxSameConversation).toBe(1);
    expect(maxOverall).toBe(2);
    expect(fake.sessions.find((s) => s.prompts[0] === "a2")!.resumed).toBe(true);
  });

  it("fails with CopilotConversationBusyError after conversationWaitMs", async () => {
    const fake = new FakeClient({ script: hangingScript });
    const { copilot } = setup({ fake, limits: { conversationWaitMs: 20 } });
    const agent = copilot.agent(bot);
    const controller = new AbortController();
    const first = agent.send({ prompt: "1", conversationId: "c", signal: controller.signal }).catch((e: unknown) => e);
    await vi.waitFor(() => expect(fake.sends).toHaveLength(1));
    await expect(agent.send({ prompt: "2", conversationId: "c" })).rejects.toBeInstanceOf(CopilotConversationBusyError);
    controller.abort();
    expect(await first).toBeInstanceOf(CopilotAbortedError);
    expect(copilot.capacity()).toMatchObject({ activeTurns: 0, queuedTurns: 0 });
  });

  it("coordinates through a SessionStore lease", async () => {
    const store = memoryStore();
    const fake = new FakeClient();
    const { copilot } = setup({ fake, store, limits: { conversationWaitMs: 30 } });
    const sessionId = deriveSessionId("bot", undefined, "c");
    // Another replica holds the lease.
    const foreign = await store.acquireLease(`copilot:conversation:${sessionId}`, 60_000, 0);
    await expect(copilot.agent(bot).send({ prompt: "x", conversationId: "c" })).rejects.toBeInstanceOf(
      CopilotConversationBusyError,
    );
    expect(fake.sends).toHaveLength(0);
    await foreign!();
    await expect(copilot.agent(bot).send({ prompt: "x", conversationId: "c" })).resolves.toBe("echo: x");
    // The lease was released after the turn.
    const again = await store.acquireLease(`copilot:conversation:${sessionId}`, 1_000, 0);
    expect(again).toBeTypeOf("function");
  });

  it("deleteConversation deletes the derived session and tolerates missing ones", async () => {
    for (const metadata of [true, false]) {
      const fake = new FakeClient({ metadata });
      const { copilot } = setup({ fake });
      const agent = copilot.agent(bot);
      await agent.send({ prompt: "x", conversationId: "c", context: { principal: "p" } });
      await agent.deleteConversation("c", { principal: "p" });
      expect(fake.persisted.size).toBe(0);
      fake.deleteSession.mockRejectedValueOnce(new Error("Session not found"));
      await expect(agent.deleteConversation("c", { principal: "p" })).resolves.toBeUndefined();
      await expect(agent.conversation("c", { principal: "p" }).send("x")).rejects.toBeInstanceOf(
        CopilotConversationNotFoundError,
      );
    }
  });
});

describe("admission", () => {
  it("rejects turns beyond maxConcurrentTurns + maxQueuedTurns", async () => {
    const fake = new FakeClient({ script: hangingScript });
    const { copilot } = setup({ fake, limits: { maxConcurrentTurns: 1, maxQueuedTurns: 1 } });
    const agent = copilot.agent(bot);
    const controller = new AbortController();
    const t1 = agent.send({ prompt: "1", signal: controller.signal }).catch((e: unknown) => e);
    const t2 = agent.send({ prompt: "2", signal: controller.signal }).catch((e: unknown) => e);
    await expect(agent.send({ prompt: "3" })).rejects.toBeInstanceOf(CopilotAdmissionRejectedError);
    await vi.waitFor(() => expect(fake.sends).toHaveLength(1));
    controller.abort();
    await t1;
    await t2;
    expect(fake.sends).toHaveLength(1);
  });

  it("an aborted queued turn leaves the queue without running", async () => {
    const release = deferred();
    const fake = new FakeClient({
      script: async (session, prompt) => {
        session.emit("user.message", { content: prompt });
        await release.promise;
        session.emit("assistant.message", { messageId: "m", content: prompt });
        session.emit("session.idle", {});
      },
    });
    const { copilot } = setup({ fake, limits: { maxConcurrentTurns: 1, maxQueuedTurns: 1 } });
    const agent = copilot.agent(bot);
    const first = agent.send({ prompt: "first" });
    await vi.waitFor(() => expect(fake.sends).toHaveLength(1));
    const controller = new AbortController();
    const queued = agent.send({ prompt: "queued", signal: controller.signal });
    await tick();
    expect(copilot.capacity().queuedTurns).toBe(1);
    controller.abort();
    await expect(queued).rejects.toBeInstanceOf(CopilotAbortedError);
    expect(copilot.capacity().queuedTurns).toBe(0);
    // The freed queue slot can be used again.
    const third = agent.send({ prompt: "third" });
    release.resolve();
    await expect(first).resolves.toBe("first");
    await expect(third).resolves.toBe("third");
    expect(fake.sends.map((s) => s.prompt)).toEqual(["first", "third"]);
  });

  it("a timeout while queued fails with CopilotTimeoutError", async () => {
    const fake = new FakeClient({ script: hangingScript });
    const { copilot } = setup({ fake, limits: { maxConcurrentTurns: 1 } });
    const agent = copilot.agent(bot);
    const controller = new AbortController();
    const first = agent.send({ prompt: "1", signal: controller.signal }).catch((e: unknown) => e);
    await vi.waitFor(() => expect(fake.sends).toHaveLength(1));
    await expect(agent.send({ prompt: "2", timeout: 20 })).rejects.toBeInstanceOf(CopilotTimeoutError);
    controller.abort();
    await first;
  });
});

describe("cancellation", () => {
  it("timeout aborts the session and rejects with CopilotTimeoutError", async () => {
    const fake = new FakeClient({ script: hangingScript });
    const { copilot } = setup({ fake });
    const error = await copilot.agent(bot).send({ prompt: "x", timeout: 30 }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(CopilotTimeoutError);
    expect(fake.lastSession.abort).toHaveBeenCalledTimes(1);
    expect(fake.lastSession.disconnect).toHaveBeenCalled();
    expect(fake.deleteSession).toHaveBeenCalled();
    expect(copilot.capacity().activeTurns).toBe(0);
  });

  it("uses responseTimeout by default", async () => {
    const fake = new FakeClient({ script: hangingScript });
    const { copilot } = setup({ fake, responseTimeout: 25 });
    await expect(copilot.agent(bot).send({ prompt: "x" })).rejects.toBeInstanceOf(CopilotTimeoutError);
  });

  it("caller abort aborts the session and rejects with CopilotAbortedError", async () => {
    const fake = new FakeClient({ script: hangingScript });
    const { copilot } = setup({ fake });
    const controller = new AbortController();
    const turn = copilot.agent(bot).send({ prompt: "x", conversationId: "c", signal: controller.signal });
    await vi.waitFor(() => expect(fake.sends).toHaveLength(1));
    controller.abort();
    await expect(turn).rejects.toBeInstanceOf(CopilotAbortedError);
    expect(fake.lastSession.abort).toHaveBeenCalledTimes(1);
    expect(fake.lastSession.disconnect).toHaveBeenCalled();
    // Persistent conversations are kept.
    expect(fake.deleteSession).not.toHaveBeenCalled();
    // The conversation lock was released.
    fake.script = replyScript("ok");
    await expect(copilot.agent(bot).send({ prompt: "y", conversationId: "c" })).resolves.toBe("ok");
  });

  it("an already-aborted signal never reaches the runtime", async () => {
    const { fake, copilot } = setup();
    const controller = new AbortController();
    controller.abort();
    await expect(copilot.agent(bot).send({ prompt: "x", signal: controller.signal })).rejects.toBeInstanceOf(
      CopilotAbortedError,
    );
    expect(fake.start).not.toHaveBeenCalled();
  });

  it("abort while the session is being created disconnects the late session", async () => {
    const fake = new FakeClient();
    const gate = deferred();
    const create = fake.createSession.bind(fake);
    fake.createSession = async (config) => {
      await gate.promise;
      return create(config);
    };
    const { copilot } = setup({ fake });
    const controller = new AbortController();
    const turn = copilot.agent(bot).send({ prompt: "x", signal: controller.signal });
    await vi.waitFor(() => expect(fake.start).toHaveBeenCalled());
    await tick();
    controller.abort();
    await expect(turn).rejects.toBeInstanceOf(CopilotAbortedError);
    gate.resolve();
    await vi.waitFor(() => expect(fake.lastSession.disconnect).toHaveBeenCalled());
    expect(fake.sends).toHaveLength(0);
  });
});

describe("stream()", () => {
  it("yields turn-start, mapped events and done", async () => {
    const { fake, copilot } = setup();
    const events = await collect(copilot.agent(bot).stream({ prompt: "hi" }));
    const types = events.map((e) => e.type);
    expect(types[0]).toBe("turn-start");
    expect(types).toContain("text-delta");
    expect(types).toContain("message");
    expect(types).toContain("usage");
    expect(types.at(-1)).toBe("done");
    expect(events.at(-1)).toEqual({ type: "done", text: "echo: hi", sessionId: fake.lastSession.sessionId });
    const deltas = events.filter((e) => e.type === "text-delta").map((e) => (e as { delta: string }).delta);
    expect(deltas.join("")).toBe("echo: hi");
    expect(fake.created[0]!.streaming).toBe(true);
  });

  it("is cold: nothing runs until iterated, and each iteration runs a new turn", async () => {
    const { fake, copilot } = setup();
    const stream = copilot.agent(bot).stream({ prompt: "x" });
    await tick();
    expect(fake.sends).toHaveLength(0);
    await collect(stream);
    await collect(stream);
    expect(fake.sends).toHaveLength(2);
  });

  it("breaking early aborts the turn and releases resources", async () => {
    const fake = new FakeClient({ script: hangingScript });
    const { copilot } = setup({ fake });
    for await (const event of copilot.agent(bot).stream({ prompt: "x", conversationId: "c" })) {
      if (event.type === "turn-start") break;
    }
    expect(fake.lastSession.abort).toHaveBeenCalledTimes(1);
    expect(fake.lastSession.disconnect).toHaveBeenCalled();
    expect(copilot.capacity().activeTurns).toBe(0);
    fake.script = replyScript("next");
    await expect(copilot.agent(bot).send({ prompt: "y", conversationId: "c" })).resolves.toBe("next");
  });

  it("delivers failures as a final error event without throwing", async () => {
    const fake = new FakeClient({
      script: async (session, prompt) => {
        session.emit("user.message", { content: prompt });
        session.emit("session.error", { errorType: "boom", message: "Exploded" });
      },
    });
    const { copilot } = setup({ fake });
    const events = await collect(copilot.agent(bot).stream({ prompt: "x" }));
    const errors = events.filter((e) => e.type === "error");
    expect(errors).toHaveLength(1);
    expect(events.at(-1)?.type).toBe("error");
    expect((errors[0] as { error: Error }).error).toBeInstanceOf(CopilotSessionError);
    expect(events.some((e) => e.type === "done")).toBe(false);
  });

  it("reports argument and admission errors as error events", async () => {
    const fake = new FakeClient({ script: hangingScript });
    const { copilot } = setup({ fake, limits: { maxConcurrentTurns: 1, maxQueuedTurns: 0 } });
    const agent = copilot.agent(bot);
    const blank = await collect(agent.stream({ prompt: "" }));
    expect(blank).toHaveLength(1);
    expect((blank[0] as { error: Error }).error).toBeInstanceOf(TypeError);
    const controller = new AbortController();
    const first = agent.send({ prompt: "1", signal: controller.signal }).catch(() => undefined);
    await vi.waitFor(() => expect(fake.sends).toHaveLength(1));
    const rejected = await collect(agent.stream({ prompt: "2" }));
    expect(rejected).toHaveLength(1);
    expect((rejected[0] as { error: Error }).error).toBeInstanceOf(CopilotAdmissionRejectedError);
    controller.abort();
    await first;
  });

  it("caller abort ends the stream with an abort error event", async () => {
    const fake = new FakeClient({ script: hangingScript });
    const { copilot } = setup({ fake });
    const controller = new AbortController();
    const events: CopilotAgentEvent[] = [];
    for await (const event of copilot.agent(bot).stream({ prompt: "x", signal: controller.signal })) {
      events.push(event);
      if (event.type === "turn-start") setTimeout(() => controller.abort(), 5);
    }
    expect(events.at(-1)?.type).toBe("error");
    expect((events.at(-1) as { error: Error }).error).toBeInstanceOf(CopilotAbortedError);
    expect(fake.lastSession.abort).toHaveBeenCalled();
  });
});

describe("permissions", () => {
  it("denies by default", async () => {
    const seen: PermissionRequestResult[] = [];
    const { copilot } = setup({ fake: new FakeClient({ script: permissionScript(shellRequest, seen) }) });
    await expect(copilot.agent(bot).send({ prompt: "x" })).resolves.toBe("permission:reject");
  });

  it("approves through the policy", async () => {
    const { copilot } = setup({ fake: new FakeClient({ script: permissionScript(shellRequest) }) });
    await expect(copilot.agent({ ...bot, permissions: permissions.allowAll() }).send({ prompt: "x" })).resolves.toBe(
      "permission:approve-once",
    );
  });

  it("a throwing policy denies and is logged", async () => {
    const { copilot, logger } = setup({ fake: new FakeClient({ script: permissionScript(shellRequest) }) });
    const agent = copilot.agent({
      ...bot,
      permissions: () => {
        throw new Error("policy bug");
      },
    });
    await expect(agent.send({ prompt: "x" })).resolves.toBe("permission:reject");
    void logger;
  });

  it("policy sees the turn context and the agent's custom tool names", async () => {
    let seen: { principal?: string; ambient?: string; tools?: string[] } = {};
    const { copilot } = setup({ fake: new FakeClient({ script: permissionScript(toolRequest("lookup")) }) });
    const agent = copilot.agent({
      ...bot,
      tools: [{ name: "lookup", description: "d", execute: () => "x" }],
      permissions: (_request, ctx) => {
        seen = {
          principal: ctx.context.principal,
          ambient: getCopilotContext()?.attributes.tenant as string,
          tools: [...ctx.customToolNames],
        };
        return "approve";
      },
    });
    await agent.send({ prompt: "x", context: { principal: "alice", attributes: { tenant: "acme" } } });
    expect(seen).toEqual({ principal: "alice", ambient: "acme", tools: ["lookup"] });
  });

  describe("ask-user in send()", () => {
    it("denies when no onPermissionRequest callback is given", async () => {
      const { copilot } = setup({ fake: new FakeClient({ script: permissionScript(shellRequest) }) });
      await expect(copilot.agent({ ...bot, permissions: permissions.askUser() }).send({ prompt: "x" })).resolves.toBe(
        "permission:reject",
      );
    });

    it("uses the callback's decision and feedback", async () => {
      const seen: PermissionRequestResult[] = [];
      const { copilot } = setup({ fake: new FakeClient({ script: permissionScript(shellRequest, seen) }) });
      const agent = copilot.agent({ ...bot, permissions: permissions.askUser() });
      const onPermissionRequest = vi.fn(() => ({ approved: true }));
      await expect(agent.send({ prompt: "x", onPermissionRequest })).resolves.toBe("permission:approve-once");
      expect(onPermissionRequest).toHaveBeenCalledWith(shellRequest);
      await expect(
        agent.send({ prompt: "x", onPermissionRequest: async () => ({ approved: false, feedback: "no way" }) }),
      ).resolves.toBe("permission:reject");
      expect(seen[1]).toEqual({ kind: "reject", feedback: "no way" });
    });

    it("returns user-not-available when the callback exceeds the approval window", async () => {
      const { copilot } = setup({
        fake: new FakeClient({ script: permissionScript(shellRequest) }),
        approvalWindow: 20,
      });
      const agent = copilot.agent({ ...bot, permissions: permissions.askUser() });
      await expect(agent.send({ prompt: "x", onPermissionRequest: () => new Promise(() => undefined) })).resolves.toBe(
        "permission:user-not-available",
      );
    });

    it("a throwing callback denies", async () => {
      const { copilot } = setup({ fake: new FakeClient({ script: permissionScript(shellRequest) }) });
      const agent = copilot.agent({ ...bot, permissions: permissions.askUser() });
      await expect(
        agent.send({
          prompt: "x",
          onPermissionRequest: () => {
            throw new Error("ui crashed");
          },
        }),
      ).resolves.toBe("permission:reject");
    });
  });

  describe("ask-user in stream()", () => {
    it("emits permission-request and applies the consumer's response", async () => {
      const { copilot } = setup({ fake: new FakeClient({ script: permissionScript(shellRequest) }) });
      const agent = copilot.agent({ ...bot, permissions: permissions.askUser() });
      const events: CopilotAgentEvent[] = [];
      for await (const event of agent.stream({ prompt: "x" })) {
        events.push(event);
        if (event.type === "permission-request") {
          expect(event.request).toEqual(shellRequest);
          event.respond({ approved: true });
          event.respond({ approved: false }); // ignored: first response wins
        }
      }
      const resolved = events.find((e) => e.type === "permission-resolved");
      expect(resolved).toMatchObject({ approved: true });
      expect(events.at(-1)).toMatchObject({ type: "done", text: "permission:approve-once" });
    });

    it("denies with feedback", async () => {
      const seen: PermissionRequestResult[] = [];
      const { copilot } = setup({ fake: new FakeClient({ script: permissionScript(shellRequest, seen) }) });
      const agent = copilot.agent({ ...bot, permissions: permissions.askUser() });
      for await (const event of agent.stream({ prompt: "x" })) {
        if (event.type === "permission-request") event.respond({ approved: false, feedback: "not now" });
      }
      expect(seen).toEqual([{ kind: "reject", feedback: "not now" }]);
    });

    it("returns user-not-available after the approval window", async () => {
      const { copilot } = setup({
        fake: new FakeClient({ script: permissionScript(shellRequest) }),
        approvalWindow: 20,
      });
      const agent = copilot.agent({ ...bot, permissions: permissions.askUser() });
      const events = await collect(agent.stream({ prompt: "x" }));
      expect(events.find((e) => e.type === "permission-resolved")).toMatchObject({ approved: false });
      expect(events.at(-1)).toMatchObject({ type: "done", text: "permission:user-not-available" });
    });

    it("breaking out while a permission is pending aborts the turn", async () => {
      const fake = new FakeClient({ script: permissionScript(shellRequest) });
      const { copilot } = setup({ fake });
      const agent = copilot.agent({ ...bot, permissions: permissions.askUser() });
      for await (const event of agent.stream({ prompt: "x" })) {
        if (event.type === "permission-request") break;
      }
      expect(fake.lastSession.abort).toHaveBeenCalled();
      expect(copilot.capacity().activeTurns).toBe(0);
    });
  });

  describe("tool needsApproval", () => {
    const approvalTool = (needsApproval: boolean | ((input: { amount: number }) => boolean)) => ({
      pay: {
        description: "Pay",
        inputSchema: z.object({ amount: z.number() }),
        execute: async () => "paid",
        needsApproval,
      },
    });

    it("forces ask-user even when the policy approves", async () => {
      const { copilot } = setup({ fake: new FakeClient({ script: permissionScript(toolRequest("pay", { amount: 5 })) }) });
      const agent = copilot.agent({ ...bot, tools: [approvalTool(true)], permissions: permissions.allowCustomTools() });
      await expect(agent.send({ prompt: "x" })).resolves.toBe("permission:reject");
      await expect(agent.send({ prompt: "x", onPermissionRequest: () => ({ approved: true }) })).resolves.toBe(
        "permission:approve-once",
      );
    });

    it("evaluates a needsApproval function against the call arguments", async () => {
      const fake = new FakeClient({ script: permissionScript(toolRequest("pay", { amount: 5 })) });
      const { copilot } = setup({ fake });
      const agent = copilot.agent({
        ...bot,
        tools: [approvalTool((input) => input.amount > 100)],
        permissions: permissions.allowCustomTools(),
      });
      await expect(agent.send({ prompt: "x" })).resolves.toBe("permission:approve-once");
      fake.script = permissionScript(toolRequest("pay", { amount: 500 }));
      await expect(agent.send({ prompt: "x" })).resolves.toBe("permission:reject");
    });

    it("a policy deny is final", async () => {
      const { copilot } = setup({ fake: new FakeClient({ script: permissionScript(toolRequest("pay", { amount: 5 })) }) });
      const agent = copilot.agent({ ...bot, tools: [approvalTool(true)], permissions: permissions.denyAll() });
      const onPermissionRequest = vi.fn(() => ({ approved: true }));
      await expect(agent.send({ prompt: "x", onPermissionRequest })).resolves.toBe("permission:reject");
      expect(onPermissionRequest).not.toHaveBeenCalled();
    });
  });
});

describe("structured output", () => {
  const Weather = z.object({ city: z.string(), temperature: z.number() });

  it("returns the validated value and sends an object JSON schema", async () => {
    const fake = new FakeClient({ script: replyScript(JSON.stringify({ city: "Paris", temperature: 21 })) });
    const { copilot } = setup({ fake });
    const value = await copilot.agent(bot).send({ prompt: "weather?", schema: Weather });
    expect(value).toEqual({ city: "Paris", temperature: 21 });
    expect(fake.responseSchemas[0]).toMatchObject({ type: "object", properties: { city: { type: "string" } } });
  });

  it("wraps non-object schemas in a value property", async () => {
    const fake = new FakeClient({ script: replyScript(JSON.stringify({ value: ["a", "b"] })) });
    const { copilot } = setup({ fake });
    await expect(copilot.agent(bot).send({ prompt: "x", schema: z.array(z.string()) })).resolves.toEqual(["a", "b"]);
    expect(fake.responseSchemas[0]).toMatchObject({ type: "object", required: ["value"] });
  });

  it("fails with CopilotOutputValidationError on schema mismatch", async () => {
    const fake = new FakeClient({ script: replyScript(JSON.stringify({ city: "Paris", temperature: "warm" })) });
    const { copilot } = setup({ fake });
    const error = await copilot.agent(bot).send({ prompt: "x", schema: Weather }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(CopilotOutputValidationError);
    expect((error as Error).cause).toBeDefined();
    expect(fake.lastSession.disconnect).toHaveBeenCalled();
  });

  it("fails with CopilotOutputValidationError on invalid JSON", async () => {
    const { copilot } = setup({ fake: new FakeClient({ script: replyScript("sunny, 21 degrees") }) });
    await expect(copilot.agent(bot).send({ prompt: "x", schema: Weather })).rejects.toBeInstanceOf(
      CopilotOutputValidationError,
    );
  });

  it("propagates session errors and timeouts", async () => {
    const { copilot } = setup({
      fake: new FakeClient({
        script: async (session) => session.emit("session.error", { errorType: "x", message: "bad" }),
      }),
    });
    await expect(copilot.agent(bot).send({ prompt: "x", schema: Weather })).rejects.toBeInstanceOf(CopilotSessionError);
    const hanging = setup({ fake: new FakeClient({ script: hangingScript }) });
    await expect(hanging.copilot.agent(bot).send({ prompt: "x", schema: Weather, timeout: 20 })).rejects.toBeInstanceOf(
      CopilotTimeoutError,
    );
    expect(hanging.fake.lastSession.abort).toHaveBeenCalled();
  });
});

describe("context propagation", () => {
  it("permission handler, hooks and tools observe the turn context", async () => {
    const observed: Record<string, CopilotContext | undefined> = {};
    const fake = new FakeClient({
      script: async (session, prompt) => {
        session.emit("user.message", { content: prompt });
        // Invoke callbacks outside the caller's async scope, as the SDK's JSON-RPC handlers do.
        await new Promise((r) => setTimeout(r, 1));
        await session.requestPermission(toolRequest("whoami"));
        const hooks = session.config.hooks!;
        await hooks.onPreToolUse!({ toolName: "whoami", toolArgs: {} } as never, { sessionId: session.sessionId });
        const tool = session.config.tools!.find((t) => t.name === "whoami")!;
        const result = await tool.handler!({}, { sessionId: session.sessionId, toolCallId: "t", toolName: "whoami", arguments: {} });
        session.emit("assistant.message", { messageId: "m", content: String(result) });
        session.emit("session.idle", {});
      },
    });
    const { copilot } = setup({
      fake,
      hooks: {
        onPreToolUse: async () => {
          observed.globalHook = getCopilotContext();
          return undefined;
        },
      },
    });
    const agent = copilot.agent({
      ...bot,
      tools: [
        {
          name: "whoami",
          description: "Who am I",
          execute: (_input, info) => info.context.principal ?? "nobody",
        },
      ],
      hooks: {
        onPreToolUse: async () => {
          observed.agentHook = getCopilotContext();
          return undefined;
        },
      },
      permissions: () => {
        observed.permission = getCopilotContext();
        return "approve";
      },
    });
    const text = await agent.send({ prompt: "x", conversationId: "conv", context: { principal: "alice" } });
    expect(text).toContain("alice");
    for (const key of ["permission", "globalHook", "agentHook"]) {
      expect(observed[key], key).toMatchObject({
        principal: "alice",
        conversationId: "conv",
        agentName: "bot",
        sessionId: deriveSessionId("bot", "alice", "conv"),
      });
      expect(observed[key]!.signal).toBeInstanceOf(AbortSignal);
    }
  });

  it("resolves per-user tokens into the context and the session config", async () => {
    const tokenResolver = vi.fn(async (ctx: CopilotContext) => `token-for-${ctx.principal}`);
    const { fake, copilot } = setup({ tokenResolver });
    await copilot.agent(bot).send({ prompt: "x", context: { principal: "alice" } });
    expect(tokenResolver).toHaveBeenCalledWith(expect.objectContaining({ principal: "alice", agentName: "bot" }));
    expect(fake.created[0]!.gitHubToken).toBe("token-for-alice");
    await copilot.agent(bot).send({ prompt: "x", context: { githubToken: "explicit" } });
    expect(fake.created[1]!.gitHubToken).toBe("explicit");
    expect(tokenResolver).toHaveBeenCalledTimes(1);
  });

  it("does not set a session token by default, but uses githubToken for external runtimes", async () => {
    const a = setup({ githubToken: "service" });
    await a.copilot.agent(bot).send({ prompt: "x" });
    expect(a.fake.created[0]!.gitHubToken).toBeUndefined();
    const b = setup({ githubToken: "service", cliUrl: "http://localhost:1" });
    await b.copilot.agent(bot).send({ prompt: "x" });
    expect(b.fake.created[0]!.gitHubToken).toBe("service");
  });

  it("configureSession token settings win", async () => {
    const { fake, copilot } = setup({ tokenResolver: () => "resolved" });
    await copilot
      .agent({ ...bot, configureSession: (config) => ({ ...config, gitHubToken: "custom" }) })
      .send({ prompt: "x" });
    expect(fake.created[0]!.gitHubToken).toBe("custom");
  });
});

describe("onEvent", () => {
  it("receives every event with the turn context", async () => {
    const events: Array<[string, string | undefined]> = [];
    const { copilot } = setup({ onEvent: (event, ctx) => events.push([event.type, ctx.principal]) });
    await copilot.agent(bot).send({ prompt: "x", context: { principal: "alice" } });
    expect(events[0]).toEqual(["turn-start", "alice"]);
    expect(events.at(-1)).toEqual(["done", "alice"]);
    expect(events.every(([, p]) => p === "alice")).toBe(true);
  });

  it("listener failures (sync and async) are logged and never break the turn", async () => {
    const { copilot, logger } = setup({
      onEvent: ((event: CopilotAgentEvent) => {
        if (event.type === "message") throw new Error("sync listener bug");
        if (event.type === "done") return Promise.reject(new Error("async listener bug"));
      }) as never,
    });
    await expect(copilot.agent(bot).send({ prompt: "x" })).resolves.toBe("echo: x");
    await vi.waitFor(() => expect(logger.warn).toHaveBeenCalledTimes(2));
    expect(logger.warn).toHaveBeenCalledWith("Copilot event listener failed", expect.objectContaining({ event: "message" }));
  });

  it("receives the error event of a failed turn", async () => {
    const events: CopilotAgentEvent[] = [];
    const { copilot } = setup({ fake: new FakeClient({ script: hangingScript }), onEvent: (e) => events.push(e) });
    await expect(copilot.agent(bot).send({ prompt: "x", timeout: 20 })).rejects.toBeInstanceOf(CopilotTimeoutError);
    const errors = events.filter((e) => e.type === "error");
    expect(errors).toHaveLength(1);
    expect((errors[0] as { error: Error }).error).toBeInstanceOf(CopilotTimeoutError);
  });
});
