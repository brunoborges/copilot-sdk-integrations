import { describe, expect, it } from "vitest";
import { createCopilot, isConnectionLost } from "../src/client.js";
import { CopilotConfigurationError, CopilotRuntimeUnavailableError } from "../src/errors.js";
import { createTestCopilot } from "../src/testing/index.js";
import type { ClientLike } from "../src/types.js";
import { FakeClient, silentLogger } from "./fakes.js";

const agent = { name: "bot" };

class DisconnectingClient extends FakeClient {
  connected = true;
  isConnected() {
    return this.connected;
  }
}

describe("runtime restart", () => {
  it("detects a crashed runtime, fails in-flight turns, and restarts on the next turn", async () => {
    await using test = createTestCopilot({
      restart: { checkIntervalMs: 20 },
      script: (rt) => rt.onPrompt(/slow/).delay(5_000).reply("late").onPrompt(/.*/).reply("ok"),
    });
    const { copilot, runtime } = test;
    const handle = copilot.agent(agent);

    await expect(handle.send({ prompt: "hi", conversationId: "c1" })).resolves.toBe("ok");
    expect(runtime.clientOptions).toHaveLength(1);

    const slow = handle.send({ prompt: "slow", conversationId: "c2" }).catch((e: unknown) => e);
    while (runtime.prompts.length < 2) await new Promise((r) => setTimeout(r, 5));
    runtime.crash();

    const error = await slow;
    expect(error).toBeInstanceOf(CopilotRuntimeUnavailableError);
    expect((error as Error).message).toMatch(/connection lost/);
    expect(copilot.state).toBe("failed");
    expect((await copilot.health()).state).toBe("failed");

    // The next turn starts a new runtime client and resumes the conversation.
    await expect(handle.send({ prompt: "again", conversationId: "c1" })).resolves.toBe("ok");
    expect(copilot.state).toBe("running");
    expect(runtime.clientOptions).toHaveLength(2);
    expect(runtime.resumedSessions.map((r) => r.sessionId)).toHaveLength(1);
  });

  it("health() reports a lost connection without restarting", async () => {
    const clients: DisconnectingClient[] = [];
    const copilot = createCopilot({
      logger: silentLogger(),
      clientFactory: () => {
        const client = new DisconnectingClient();
        clients.push(client);
        return client;
      },
    });
    await copilot.start();
    clients[0]!.connected = false;
    expect((await copilot.health()).state).toBe("failed");
    expect(clients[0]!.stop).toHaveBeenCalled();
    expect(clients).toHaveLength(1);
    await expect(copilot.agent(agent).send({ prompt: "x" })).resolves.toBe("echo: x");
    expect(clients).toHaveLength(2);
    await copilot.stop();
  });

  it("backs off between consecutive failed starts and gives up after maxAttempts until start()", async () => {
    let failing = true;
    let attempts = 0;
    const copilot = createCopilot({
      logger: silentLogger(),
      restart: { maxAttempts: 3, backoffMs: 50, maxBackoffMs: 80 },
      clientFactory: () =>
        new FakeClient({
          start: async () => {
            attempts++;
            if (failing) throw new Error("spawn ENOENT");
          },
        }),
    });
    const send = () => copilot.agent(agent).send({ prompt: "x" });

    await expect(send()).rejects.toBeInstanceOf(CopilotRuntimeUnavailableError);
    let started = Date.now();
    await expect(send()).rejects.toBeInstanceOf(CopilotRuntimeUnavailableError); // immediate retry
    expect(Date.now() - started).toBeLessThan(40);
    started = Date.now();
    await expect(send()).rejects.toBeInstanceOf(CopilotRuntimeUnavailableError); // waits backoffMs
    expect(Date.now() - started).toBeGreaterThanOrEqual(45);
    expect(attempts).toBe(3);

    // maxAttempts reached: turns fail fast without trying.
    await expect(send()).rejects.toThrow(/after 3 failed start attempts/);
    expect(attempts).toBe(3);
    expect(copilot.state).toBe("failed");

    failing = false;
    await copilot.start(); // explicit start resets the attempts
    expect(attempts).toBe(4);
    await expect(send()).resolves.toBe("echo: x");
    await copilot.stop();
  });

  it("maxAttempts: 0 disables automatic restarts after a crash", async () => {
    await using test = createTestCopilot({ restart: { maxAttempts: 0 }, script: (rt) => rt.onPrompt(/.*/).reply("ok") });
    const handle = test.copilot.agent(agent);
    await expect(handle.send({ prompt: "a" })).resolves.toBe("ok");
    test.runtime.crash();
    await expect(handle.send({ prompt: "b" })).rejects.toThrow(/automatic restarts are disabled/);
    await test.copilot.start();
    await expect(handle.send({ prompt: "c" })).resolves.toBe("ok");
  });

  it("stop() cancels a pending backoff", async () => {
    let attempts = 0;
    const copilot = createCopilot({
      logger: silentLogger(),
      restart: { backoffMs: 10_000 },
      clientFactory: () =>
        new FakeClient({
          start: async () => {
            attempts++;
            throw new Error("down");
          },
        }),
    });
    await expect(copilot.start()).rejects.toThrow();
    await expect(copilot.client()).rejects.toThrow();
    const waiting = copilot.client().catch((e: unknown) => e);
    await new Promise((r) => setTimeout(r, 10));
    const started = Date.now();
    await copilot.stop({ gracePeriodMs: 10 });
    expect(Date.now() - started).toBeLessThan(1_000);
    expect(await waiting).toBeInstanceOf(Error);
    expect(attempts).toBe(2);
  });

  it("validates restart options", () => {
    expect(() => createCopilot({ restart: { maxAttempts: -1 } })).toThrow(CopilotConfigurationError);
    expect(() => createCopilot({ restart: { backoffMs: 0 } })).toThrow(/restart.backoffMs/);
    expect(() => createCopilot({ restart: { checkIntervalMs: -5 } })).toThrow(/restart.checkIntervalMs/);
  });

  it("isConnectionLost uses isConnected() or the SDK client's rpc accessor", () => {
    const base = new FakeClient() as ClientLike;
    expect(isConnectionLost(base)).toBe(false);
    expect(isConnectionLost(Object.assign(new FakeClient(), { isConnected: () => false }))).toBe(true);
    const sdkLike = Object.defineProperty(new FakeClient(), "rpc", {
      get() {
        throw new Error("Client is not connected. Call start() first.");
      },
    });
    expect(isConnectionLost(sdkLike)).toBe(true);
  });
});

describe("root exports", () => {
  it("exposes the governance and MCP helpers", async () => {
    const core = await import("../src/index.js");
    expect(core.PermissionKinds.SHELL).toBe("shell");
    expect(core.ALL_BUILT_IN_TOOLS).toBe("builtin:*");
    expect(core.buildToolFilterConfig({ name: "a" })).toEqual({ excludedTools: ["builtin:*"] });
    expect(() => core.validateToolFilters({ name: "a", availableTools: ["x"], excludedTools: ["y"] }, { customToolNames: new Set() })).toThrow(
      core.CopilotConfigurationError,
    );
    expect(await core.resolveMcpServers({ gh: { type: "http", url: "https://x", headers: (c) => ({ user: c.principal }) } }, { principal: "u1", attributes: {} })).toEqual({
      gh: { type: "http", url: "https://x", headers: { user: "u1" } },
    });
  });
});
