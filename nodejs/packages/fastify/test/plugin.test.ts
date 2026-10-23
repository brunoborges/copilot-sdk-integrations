import {
  CopilotConfigurationError,
  createCopilot,
  defineAgent,
} from "@copilot-sdk-integrations/agent-core";
import { silentLogger } from "@copilot-sdk-integrations/agent-core/testing";
import { describe, expect, it, vi } from "vitest";
import { copilotPlugin } from "../src/index.js";
import { app, setup, testCopilot, waitFor } from "./helpers.js";

const support = defineAgent({ name: "support", instructions: "Help." });
const sales = defineAgent({ name: "sales" });

describe("registration", () => {
  it("decorates the instance, request, and reply", async () => {
    const { fastify } = await setup({ agents: [support] });
    fastify.get("/", async (request, reply) => ({
      instance: typeof fastify.copilot.agent,
      request: typeof request.copilot.agent,
      reply: typeof reply.copilotEvents,
    }));
    const res = await fastify.inject("/");
    expect(res.json()).toEqual({ instance: "function", request: "function", reply: "function" });
    expect(fastify.copilot.state).toBe("not-started");
  });

  it.each([
    [{ startup: "soon" }, /"startup"/],
    [{ shutdownGracePeriod: -1 }, /"shutdownGracePeriod"/],
    [{ health: { path: "health" } }, /"health\.path"|"health"/],
    [{ context: { hook: "onSend" } }, /"context\.hook"|"context"/],
    [{ agents: [{}] }, /"agents\.0"/],
    [{ bogus: 1 }, /unknown option "bogus"/],
    [{ context: { principal: "alice" } }, /"context\.principal"|"context"/],
  ])("rejects invalid options %o naming the key", async (options, message) => {
    const fastify = app();
    const error = await fastify.register(copilotPlugin, options as never).ready().catch((e: unknown) => e);
    expect(error).toBeInstanceOf(CopilotConfigurationError);
    expect((error as Error).message).toMatch(/^Invalid copilotPlugin options: /);
    expect((error as Error).message).toMatch(message);
  });

  it("creates and owns a client from CopilotOptions, logging through fastify.log", async () => {
    const test = testCopilot({ script: (rt) => rt.onPrompt(/.*/).reply("ok") });
    const fastify = app();
    await fastify.register(copilotPlugin, { client: { clientFactory: test.runtime.clientFactory, logger: silentLogger() }, agents: [support] });
    await expect(fastify.copilot.agent("support").send({ prompt: "hi" })).resolves.toBe("ok");
    expect(fastify.copilot.core).not.toBe(test.copilot);
    await fastify.close();
    expect(fastify.copilot.state).toBe("stopped");
  });

  it("supports one client per encapsulated child scope", async () => {
    const a = testCopilot({ script: (rt) => rt.onPrompt(/.*/).reply("A") });
    const b = testCopilot({ script: (rt) => rt.onPrompt(/.*/).reply("B") });
    const fastify = app();
    for (const [prefix, test] of [["/a", a], ["/b", b]] as const) {
      await fastify.register(
        async (child) => {
          await child.register(copilotPlugin, { client: test.copilot, agents: [support], health: true });
          child.get("/chat", async (request) => request.copilot.agent("support").send({ prompt: "hi" }));
        },
        { prefix },
      );
    }
    expect((await fastify.inject("/a/chat")).body).toBe("A");
    expect((await fastify.inject("/b/chat")).body).toBe("B");
    expect((await fastify.inject("/a/health/copilot")).statusCode).toBe(200);
    expect(fastify.hasDecorator("copilot")).toBe(false);
  });

  it("copilotPlugin.scoped creates its own encapsulated scope", async () => {
    const { copilot } = testCopilot();
    const fastify = app();
    await fastify.register(copilotPlugin.scoped, { client: copilot, health: true, prefix: "/tenant" });
    expect((await fastify.inject("/tenant/health/copilot")).statusCode).toBe(200);
    expect(fastify.hasDecorator("copilot")).toBe(false);
    expect(fastify.hasRequestDecorator("copilot")).toBe(false);
  });
});

describe("agents", () => {
  it("looks agents up by name and lists them", async () => {
    const { fastify, test } = await setup({ agents: [support, sales] }, { script: (rt) => rt.onPrompt(/.*/).reply("hello") });
    expect(fastify.copilot.agents().map((a) => a.name)).toEqual(["support", "sales"]);
    expect(fastify.copilot.agent("support").definition.name).toBe("support");
    expect(fastify.copilot.agent("support")).toBe(fastify.copilot.agent("support"));
    await expect(fastify.copilot.agent("support").send({ prompt: "hi" })).resolves.toBe("hello");
    expect(test.runtime.prompts).toEqual(["hi"]);
  });

  it("throws a configuration error listing registered agents for an unknown name", async () => {
    const { fastify } = await setup({ agents: [support, sales] });
    expect(() => fastify.copilot.agent("nope")).toThrow(CopilotConfigurationError);
    expect(() => fastify.copilot.agent("nope")).toThrow(/"nope".*"support", "sales"/);
    const { fastify: empty } = await setup();
    expect(() => empty.copilot.agent("nope")).toThrow(/\(none\)/);
  });

  it("registers agents before ready, rejects duplicates and late registration", async () => {
    const { fastify } = await setup({ agents: [support] });
    fastify.copilot.registerAgent(sales);
    expect(() => fastify.copilot.registerAgent(defineAgent({ name: "support" }))).toThrow(/already registered/);
    await fastify.ready();
    expect(() => fastify.copilot.registerAgent(defineAgent({ name: "late" }))).toThrow(/after the application is ready/);
    expect(fastify.copilot.agents().map((a) => a.name)).toEqual(["support", "sales"]);
  });

  it("rejects duplicate names in the agents option", async () => {
    const fastify = app();
    await expect(fastify.register(copilotPlugin, { agents: [support, support] }).ready()).rejects.toThrow(/already registered/);
  });

  it("merges agentOverrides over definitions and warns about unknown names", async () => {
    const warn = vi.fn();
    const { fastify, test } = await setup(
      { agents: [support], agentOverrides: { support: { model: "gpt-5-mini" }, ghost: { model: "x" } } },
      { script: (rt) => rt.onPrompt(/.*/).reply("ok") },
      (f) => {
        f.addHook("onReady", async () => {});
        vi.spyOn(f.log, "warn").mockImplementation(warn as never);
      },
    );
    expect(fastify.copilot.agent("support").definition).toMatchObject({ name: "support", model: "gpt-5-mini", instructions: "Help." });
    await fastify.copilot.agent("support").send({ prompt: "hi" });
    expect(test.runtime.createdSessions[0]).toMatchObject({ model: "gpt-5-mini" });
    await fastify.ready();
    expect(warn).toHaveBeenCalledWith(expect.anything(), expect.stringMatching(/ghost/));
  });

  it("returns handles for definitions too", async () => {
    const { fastify } = await setup();
    expect(fastify.copilot.agent(support).definition).toBe(support);
  });
});

describe("lifecycle", () => {
  it("starts lazily by default", async () => {
    const { fastify, test } = await setup();
    await fastify.ready();
    expect(test.copilot.state).toBe("not-started");
  });

  it("starts eagerly in onReady", async () => {
    const { fastify, test } = await setup({ startup: "eager" });
    await fastify.ready();
    expect(test.copilot.state).toBe("running");
  });

  it("fails ready() when an eager start fails", async () => {
    const { fastify, test } = await setup({ startup: "eager" }, { script: (rt) => rt.failStart("no runtime") });
    await expect(fastify.ready()).rejects.toThrow(/no runtime/);
    expect(test.copilot.state).toBe("failed");
  });

  it("does not stop a client it did not create (unless stopOnClose)", async () => {
    const { fastify, test } = await setup({ startup: "eager" });
    await fastify.ready();
    await fastify.close();
    expect(test.copilot.state).toBe("running");

    const other = await setup({ startup: "eager", stopOnClose: true });
    await other.fastify.ready();
    await other.fastify.close();
    expect(other.test.copilot.state).toBe("stopped");
  });

  it("can keep an owned client running with stopOnClose: false", async () => {
    const test = testCopilot();
    const fastify = app();
    await fastify.register(copilotPlugin, { client: { clientFactory: test.runtime.clientFactory, logger: silentLogger() }, startup: "eager", stopOnClose: false });
    await fastify.ready();
    await fastify.close();
    expect(fastify.copilot.state).toBe("running");
    await fastify.copilot.stop({ gracePeriodMs: 0 });
  });

  it("drains in-flight turns on close, then stops", async () => {
    const { fastify, test } = await setup(
      { agents: [support], stopOnClose: true, shutdownGracePeriod: 5_000 },
      { script: (rt) => rt.onPrompt(/.*/).delay(100).reply("finished") },
    );
    const turn = fastify.copilot.agent("support").send({ prompt: "slow" });
    await waitFor(() => test.runtime.prompts.length === 1);
    const closing = fastify.close();
    await waitFor(() => test.copilot.state === "stopping");
    await expect(fastify.copilot.agent("support").send({ prompt: "late" })).rejects.toThrow(/shutting down|stopping/i);
    await expect(turn).resolves.toBe("finished");
    await closing;
    expect(test.copilot.state).toBe("stopped");
  });

  it("aborts turns that outlive shutdownGracePeriod", async () => {
    const { fastify, test } = await setup(
      { agents: [support], stopOnClose: true, shutdownGracePeriod: 20 },
      { script: (rt) => rt.onPrompt(/.*/).delay(5_000).reply("never") },
    );
    const turn = fastify.copilot.agent("support").send({ prompt: "slow" }).catch((e: unknown) => e);
    await waitFor(() => test.runtime.prompts.length === 1);
    await fastify.close();
    expect(await turn).toBeInstanceOf(Error);
    expect(test.copilot.state).toBe("stopped");
  });
});

describe("existing Copilot detection", () => {
  it("treats objects with the Copilot methods as an existing client", async () => {
    const copilot = createCopilot({ logger: silentLogger(), clientFactory: testCopilot().runtime.clientFactory });
    const fastify = app();
    await fastify.register(copilotPlugin, { client: copilot });
    expect(fastify.copilot.core).toBe(copilot);
    await copilot.stop({ gracePeriodMs: 0 });
  });
});
