import { defineAgent } from "@copilot-sdk-integrations/core";
import Fastify from "fastify";
import { describe, expect, it } from "vitest";
import { copilotPlugin, type CopilotPluginOptions } from "../src/index.js";
import { buildCopilotTestApp, parseSse } from "../src/testing/index.js";

const support = defineAgent({ name: "support" });

async function buildApp(options: Pick<CopilotPluginOptions, "client">) {
  const app = Fastify();
  await app.register(copilotPlugin, { ...options, agents: [support] });
  app.get("/ask", async (request) => request.copilot.agent("support").send({ prompt: "hi" }));
  app.get("/stream", async (request, reply) => reply.copilotEvents(request.copilot.agent("support").stream({ prompt: "hi" })));
  return app;
}

describe("buildCopilotTestApp", () => {
  it("builds the app against a scripted runtime and stops it on close", async () => {
    const { app, runtime, copilot } = await buildCopilotTestApp(buildApp, (rt) => rt.onPrompt(/hi/).reply("hello"));
    expect((await app.inject("/ask")).body).toBe("hello");
    expect(parseSse((await app.inject("/stream")).body).at(-1)).toMatchObject({ event: "done", data: { text: "hello" } });
    expect(runtime.prompts).toEqual(["hi", "hi"]);
    expect(app.copilot.core).toBe(copilot);
    await app.close();
    expect(copilot.state).toBe("stopped");
  });

  it("stops the test client when the factory throws", async () => {
    await expect(
      buildCopilotTestApp(async () => {
        throw new Error("bad app");
      }),
    ).rejects.toThrow("bad app");
  });
});
