import { afterEach, describe, expect, it, vi } from "vitest";
import { RuntimeConnection } from "@github/copilot-sdk";
import { defineAgent } from "../src/define-agent.js";
import { buildClientOptions, createCopilot } from "../src/client.js";
import {
  CopilotConfigurationError,
  CopilotRuntimeUnavailableError,
  CopilotShuttingDownError,
} from "../src/errors.js";
import { FakeClient, deferred, hangingScript, silentLogger, tick } from "./fakes.js";

const agentDef = { name: "bot", instructions: "Be terse." };

afterEach(() => vi.useRealTimers());

describe("buildClientOptions", () => {
  it("always uses empty mode and defaults baseDirectory", () => {
    const options = buildClientOptions({}, {});
    expect(options.mode).toBe("empty");
    expect(options.baseDirectory).toMatch(/\.copilot$/);
    expect(options.connection).toBeUndefined();
  });

  it("honors COPILOT_CLI_PATH and COPILOT_HOME, with explicit options winning", () => {
    const env = { COPILOT_CLI_PATH: "/env/copilot", COPILOT_HOME: "/env/home" };
    const fromEnv = buildClientOptions({}, env);
    expect(fromEnv.baseDirectory).toBe("/env/home");
    expect(fromEnv.connection).toEqual(RuntimeConnection.forStdio({ path: "/env/copilot" }));
    const explicit = buildClientOptions({ cliPath: "/opt/copilot", baseDirectory: "/data" }, env);
    expect(explicit.connection).toEqual(RuntimeConnection.forStdio({ path: "/opt/copilot" }));
    expect(explicit.baseDirectory).toBe("/data");
  });

  it("passes token, logged-in user and log level", () => {
    const options = buildClientOptions({ githubToken: "ghs_x", useLoggedInUser: false, logLevel: "debug" }, {});
    expect(options.gitHubToken).toBe("ghs_x");
    expect(options.useLoggedInUser).toBe(false);
    expect(options.logLevel).toBe("debug");
  });

  it("connects to an external runtime by URL without a client-level token", () => {
    const options = buildClientOptions({ cliUrl: "http://localhost:9000", githubToken: "ghs_x" }, {});
    expect(options.connection).toEqual(RuntimeConnection.forUri("http://localhost:9000"));
    expect(options.gitHubToken).toBeUndefined();
    expect(options.baseDirectory).toBeUndefined();
  });

  it("rejects conflicting connection options", () => {
    expect(() => buildClientOptions({ cliUrl: "http://x", cliPath: "/y" }, {})).toThrow(CopilotConfigurationError);
    expect(() => buildClientOptions({ cliUrl: "http://x", useLoggedInUser: true }, {})).toThrow(CopilotConfigurationError);
  });
});

describe("createCopilot", () => {
  it("validates options", () => {
    expect(() => createCopilot({ limits: { maxConcurrentTurns: 0 } })).toThrow(CopilotConfigurationError);
    expect(() => createCopilot({ limits: { maxQueuedTurns: -1 } })).toThrow(CopilotConfigurationError);
    expect(() => createCopilot({ responseTimeout: 0 })).toThrow(CopilotConfigurationError);
    expect(() => createCopilot({ startup: "soon" as never })).toThrow(CopilotConfigurationError);
    expect(() => createCopilot({ cliUrl: "http://x", cliPath: "/y" })).toThrow(CopilotConfigurationError);
  });

  it("starts lazily, once, shared by concurrent callers", async () => {
    const gate = deferred();
    const fake = new FakeClient({ start: () => gate.promise });
    const factory = vi.fn(() => fake);
    const copilot = createCopilot({ clientFactory: factory, logger: silentLogger() });
    expect(copilot.state).toBe("not-started");
    expect(factory).not.toHaveBeenCalled();
    const a = copilot.client();
    const b = copilot.client();
    const c = copilot.agent(agentDef).send({ prompt: "hi" });
    await tick();
    expect(copilot.state).toBe("starting");
    gate.resolve();
    expect(await a).toBe(fake);
    expect(await b).toBe(fake);
    expect(await c).toBe("echo: hi");
    expect(factory).toHaveBeenCalledTimes(1);
    expect(fake.start).toHaveBeenCalledTimes(1);
    expect(copilot.state).toBe("running");
  });

  it("passes built options through configureClient to the factory", async () => {
    const factory = vi.fn(() => new FakeClient());
    const copilot = createCopilot({
      clientFactory: factory,
      baseDirectory: "/data",
      configureClient: (o) => ({ ...o, logLevel: "error" }),
      logger: silentLogger(),
    });
    await copilot.start();
    expect(factory).toHaveBeenCalledWith(expect.objectContaining({ mode: "empty", baseDirectory: "/data", logLevel: "error" }));
  });

  it("start failure marks the client failed, wraps the error, and a later call retries", async () => {
    let attempts = 0;
    const clients: FakeClient[] = [];
    const copilot = createCopilot({
      logger: silentLogger(),
      clientFactory: () => {
        const fake = new FakeClient({
          start: async () => {
            if (++attempts === 1) throw new Error("spawn ENOENT");
          },
        });
        clients.push(fake);
        return fake;
      },
    });
    const error = await copilot.start().catch((e: unknown) => e);
    expect(error).toBeInstanceOf(CopilotRuntimeUnavailableError);
    expect((error as Error).cause).toBeInstanceOf(Error);
    expect(copilot.state).toBe("failed");
    expect(clients[0]!.stop).toHaveBeenCalled();
    await expect(copilot.agent(agentDef).send({ prompt: "x" })).resolves.toBe("echo: x");
    expect(copilot.state).toBe("running");
    expect(clients).toHaveLength(2);
  });

  it("health() never starts a lazy client", async () => {
    const factory = vi.fn(() => new FakeClient());
    const copilot = createCopilot({ clientFactory: factory, logger: silentLogger() });
    const health = await copilot.health();
    expect(health.state).toBe("not-started");
    expect(health.version).toBeUndefined();
    expect(health.capacity).toEqual({ maxConcurrentTurns: 16, activeTurns: 0, maxQueuedTurns: 64, queuedTurns: 0 });
    expect(factory).not.toHaveBeenCalled();
    await copilot.start();
    expect(await copilot.health()).toMatchObject({ state: "running", version: "9.9.9" });
  });

  it("health() tolerates a failing status call", async () => {
    const fake = new FakeClient();
    fake.getStatus.mockRejectedValue(new Error("down"));
    const copilot = createCopilot({ clientFactory: () => fake, logger: silentLogger() });
    await copilot.start();
    expect(await copilot.health()).toMatchObject({ state: "running", version: undefined });
  });

  it("caches agent handles per definition", () => {
    const copilot = createCopilot({ clientFactory: () => new FakeClient(), logger: silentLogger() });
    const def = { name: "a" };
    expect(copilot.agent(def)).toBe(copilot.agent(def));
    expect(copilot.agent(def).definition).toEqual(def);
    expect(Object.isFrozen(copilot.agent(def).definition)).toBe(true);
    const frozen = defineAgent({ name: "b" });
    expect(copilot.agent(frozen).definition).toBe(frozen);
  });

  it("reports capacity while turns run and queue", async () => {
    const fake = new FakeClient({ script: hangingScript });
    const copilot = createCopilot({
      clientFactory: () => fake,
      limits: { maxConcurrentTurns: 1, maxQueuedTurns: 1 },
      logger: silentLogger(),
    });
    const agent = copilot.agent(agentDef);
    const c1 = new AbortController();
    const c2 = new AbortController();
    const t1 = agent.send({ prompt: "1", signal: c1.signal }).catch((e: unknown) => e);
    const t2 = agent.send({ prompt: "2", signal: c2.signal }).catch((e: unknown) => e);
    await vi.waitFor(() => expect(fake.sends).toHaveLength(1));
    expect(copilot.capacity()).toEqual({ maxConcurrentTurns: 1, activeTurns: 1, maxQueuedTurns: 1, queuedTurns: 1 });
    c1.abort();
    c2.abort();
    await t1;
    await t2;
    expect(copilot.capacity()).toMatchObject({ activeTurns: 0, queuedTurns: 0 });
  });

  describe("stop()", () => {
    it("stops a never-started client without starting it, and is terminal", async () => {
      const factory = vi.fn(() => new FakeClient());
      const copilot = createCopilot({ clientFactory: factory, logger: silentLogger() });
      await copilot.stop();
      expect(copilot.state).toBe("stopped");
      expect(factory).not.toHaveBeenCalled();
      await expect(copilot.client()).rejects.toBeInstanceOf(CopilotShuttingDownError);
      await expect(copilot.start()).rejects.toBeInstanceOf(CopilotShuttingDownError);
      await expect(copilot.agent(agentDef).send({ prompt: "x" })).rejects.toBeInstanceOf(CopilotShuttingDownError);
    });

    it("waits for in-flight turns within the grace period", async () => {
      const release = deferred();
      const fake = new FakeClient({
        script: async (session, prompt) => {
          session.emit("user.message", { content: prompt });
          await release.promise;
          session.emit("assistant.message", { messageId: "m", content: "finished" });
          session.emit("session.idle", {});
        },
      });
      const copilot = createCopilot({ clientFactory: () => fake, logger: silentLogger() });
      const turn = copilot.agent(agentDef).send({ prompt: "x" });
      await vi.waitFor(() => expect(fake.sends).toHaveLength(1));
      const stopping = copilot.stop({ gracePeriodMs: 5_000 });
      await tick();
      expect(copilot.state).toBe("stopping");
      await expect(copilot.agent(agentDef).send({ prompt: "late" })).rejects.toBeInstanceOf(CopilotShuttingDownError);
      expect(fake.stop).not.toHaveBeenCalled();
      release.resolve();
      await expect(turn).resolves.toBe("finished");
      await stopping;
      expect(fake.stop).toHaveBeenCalledTimes(1);
      expect(fake.lastSession.abort).not.toHaveBeenCalled();
      expect(copilot.state).toBe("stopped");
    });

    it("aborts turns that outlive the grace period", async () => {
      const fake = new FakeClient({ script: hangingScript });
      const copilot = createCopilot({ clientFactory: () => fake, logger: silentLogger() });
      const turn = copilot.agent(agentDef).send({ prompt: "x" }).catch((e: unknown) => e);
      await vi.waitFor(() => expect(fake.sends).toHaveLength(1));
      await copilot.stop({ gracePeriodMs: 20 });
      expect(await turn).toBeInstanceOf(CopilotShuttingDownError);
      expect(fake.lastSession.abort).toHaveBeenCalled();
      expect(fake.lastSession.disconnect).toHaveBeenCalled();
      expect(fake.stop).toHaveBeenCalledTimes(1);
    });

    it("rejects turns queued for a slot", async () => {
      const fake = new FakeClient({ script: hangingScript });
      const copilot = createCopilot({
        clientFactory: () => fake,
        limits: { maxConcurrentTurns: 1 },
        logger: silentLogger(),
      });
      const agent = copilot.agent(agentDef);
      const first = agent.send({ prompt: "1" }).catch((e: unknown) => e);
      await vi.waitFor(() => expect(fake.sends).toHaveLength(1));
      const queued = agent.send({ prompt: "2" }).catch((e: unknown) => e);
      await tick();
      await copilot.stop({ gracePeriodMs: 10 });
      expect(await queued).toBeInstanceOf(CopilotShuttingDownError);
      expect(await first).toBeInstanceOf(CopilotShuttingDownError);
      expect(fake.sends).toHaveLength(1);
    });

    it("is idempotent and stops a client that is still starting", async () => {
      const gate = deferred();
      const fake = new FakeClient({ start: () => gate.promise });
      const copilot = createCopilot({ clientFactory: () => fake, logger: silentLogger() });
      const starting = copilot.start().catch((e: unknown) => e);
      await tick();
      const s1 = copilot.stop();
      const s2 = copilot.stop();
      gate.resolve();
      await Promise.all([s1, s2]);
      expect(await starting).toBeInstanceOf(CopilotShuttingDownError);
      expect(fake.stop).toHaveBeenCalledTimes(1);
      expect(copilot.state).toBe("stopped");
    });

    it("logs runtime stop errors instead of throwing", async () => {
      const fake = new FakeClient();
      fake.stop.mockResolvedValue([new Error("leftover session")]);
      const logger = silentLogger();
      const copilot = createCopilot({ clientFactory: () => fake, logger });
      await copilot.start();
      await copilot.stop();
      expect(logger.warn).toHaveBeenCalledWith(expect.stringContaining("stopping"), expect.anything());
    });
  });
});
