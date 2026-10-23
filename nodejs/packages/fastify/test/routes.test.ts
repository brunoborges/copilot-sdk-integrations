import underPressure from "@fastify/under-pressure";
import { defineAgent, defineCopilotTool } from "@copilot-sdk-integrations/agent-core";
import { describe, expect, it, vi } from "vitest";
import { copilotHealthCheck, copilotPlugin } from "../src/index.js";
import { app, setup, testCopilot } from "./helpers.js";

const support = defineAgent({
  name: "support",
  description: "Customer support",
  model: "gpt-5",
  instructions: "Secret instructions",
  tools: [defineCopilotTool({ name: "lookup", description: "Lookup", execute: () => "x" })],
});

describe("health route", () => {
  it("is off by default", async () => {
    const { fastify } = await setup();
    expect((await fastify.inject("/health/copilot")).statusCode).toBe(404);
  });

  it("reports state without starting a lazy client", async () => {
    const { fastify, test } = await setup({ health: true });
    const res = await fastify.inject("/health/copilot");
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ state: "not-started", capacity: { maxConcurrentTurns: expect.any(Number), activeTurns: 0, maxQueuedTurns: expect.any(Number), queuedTurns: 0 } });
    expect(test.copilot.state).toBe("not-started");
  });

  it("reports 503 after a crash and 200 again once the next turn restarts the runtime", async () => {
    const { fastify, test } = await setup({ health: { path: "/healthz" }, agents: [support] }, { script: (rt) => rt.onPrompt(/.*/).reply("ok") });
    await fastify.copilot.agent("support").send({ prompt: "a" });
    expect((await fastify.inject("/healthz")).json()).toMatchObject({ state: "running" });
    test.runtime.crash();
    const down = await fastify.inject("/healthz");
    expect(down.statusCode).toBe(503);
    expect(down.json()).toMatchObject({ state: "failed" });
    await fastify.copilot.agent("support").send({ prompt: "b" });
    const up = await fastify.inject("/healthz");
    expect(up.statusCode).toBe(200);
    expect(up.json()).toMatchObject({ state: "running" });
  });

  it("reports 503 while stopped and when start failed", async () => {
    const { fastify, test } = await setup({ health: true }, { script: (rt) => rt.failStart("down") });
    await test.copilot.start().catch(() => undefined);
    expect((await fastify.inject("/health/copilot")).statusCode).toBe(503);
    await test.copilot.stop({ gracePeriodMs: 0 });
    const res = await fastify.inject("/health/copilot");
    expect(res.statusCode).toBe(503);
    expect(res.json()).toMatchObject({ state: "stopped" });
  });

  it("respects the register prefix", async () => {
    const { copilot } = testCopilot();
    const fastify = app();
    await fastify.register(copilotPlugin, { client: copilot, health: true, prefix: "/api" });
    fastify.get("/root", async () => typeof fastify.copilot);
    expect((await fastify.inject("/api/health/copilot")).statusCode).toBe(200);
    expect((await fastify.inject("/health/copilot")).statusCode).toBe(404);
    expect((await fastify.inject("/root")).body).toBe("object");
  });

  it("works as an @fastify/under-pressure health check", async () => {
    const { fastify, test } = await setup({}, { script: (rt) => rt.failStart("down") });
    await fastify.register(underPressure, {
      healthCheck: copilotHealthCheck(fastify),
      healthCheckInterval: 60_000,
      exposeStatusRoute: "/status",
    });
    expect((await fastify.inject("/status")).statusCode).toBe(200);
    await test.copilot.start().catch(() => undefined);
    expect(test.copilot.state).toBe("failed");
    expect(await copilotHealthCheck(fastify)()).toBe(false);
    expect((await fastify.inject("/status")).statusCode).toBe(503);
  });
});

describe("info route", () => {
  it("describes agents, state, and capacity without instructions", async () => {
    const { fastify } = await setup({ agents: [support], info: true });
    const res = await fastify.inject("/copilot/info");
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({
      state: "not-started",
      capacity: expect.objectContaining({ activeTurns: 0 }),
      agents: [{ name: "support", description: "Customer support", model: "gpt-5", builtInTools: false, tools: ["lookup"], mcpServers: [] }],
    });
    expect(res.body).not.toContain("Secret");
  });

  it("supports a guard, a custom path, includeInstructions, and a prefix", async () => {
    const { copilot } = testCopilot();
    const fastify = app();
    await fastify.register(copilotPlugin, {
      client: copilot,
      agents: [support],
      prefix: "/admin",
      info: {
        path: "/agents",
        includeInstructions: true,
        onRequest: async (request, reply) => {
          if (request.headers.authorization !== "Bearer ok") return reply.code(401).send({ error: "unauthorized" });
        },
      },
    });
    expect((await fastify.inject("/admin/agents")).statusCode).toBe(401);
    const res = await fastify.inject({ url: "/admin/agents", headers: { authorization: "Bearer ok" } });
    expect(res.statusCode).toBe(200);
    expect(res.json().agents[0].instructions).toBe("Secret instructions");
  });

  it("includes toolSearch settings", async () => {
    const searcher = defineAgent({ name: "searcher", toolSearch: { enabled: true, deferThreshold: 12 } });
    const { fastify } = await setup({ agents: [searcher], info: true });
    const res = await fastify.inject("/copilot/info");
    expect(res.json().agents[0].toolSearch).toEqual({ enabled: true, deferThreshold: 12 });
  });

  it("warns when the info route has no guard", async () => {
    const warn = vi.fn();
    await setup({ info: true }, {}, (f) => {
      vi.spyOn(f.log, "warn").mockImplementation(warn as never);
    });
    expect(warn).toHaveBeenCalledWith(expect.anything(), expect.stringMatching(/no onRequest guard/));
  });
});
