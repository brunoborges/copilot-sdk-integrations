import { defineAgent, defineCopilotTool, getCopilotContext, type CopilotContext } from "@copilot-sdk-integrations/agent-core";
import type { FastifyRequest } from "fastify";
import { describe, expect, it } from "vitest";
import { setup } from "./helpers.js";

function whoamiAgent(seen: (CopilotContext | undefined)[]) {
  return defineAgent({
    name: "support",
    tools: [
      defineCopilotTool({
        name: "whoami",
        description: "Who is asking",
        skipPermission: true,
        execute: () => {
          const context = getCopilotContext();
          seen.push(context);
          return context?.principal ?? "nobody";
        },
      }),
    ],
  });
}

const script = (rt: Parameters<NonNullable<Parameters<typeof setup>[1]["script"]>>[0]) =>
  rt.onPrompt(/who/).callTool("whoami").thenReply("done");

function authenticate(fastify: Awaited<ReturnType<typeof setup>>["fastify"]) {
  fastify.decorateRequest("user", null);
  fastify.addHook("onRequest", async (request) => {
    const id = request.headers["x-user"];
    if (typeof id === "string") (request as FastifyRequest & { user: unknown }).user = { id, role: "admin" };
  });
}

describe("request context", () => {
  it("passes the principal to tools through getCopilotContext()", async () => {
    const seen: (CopilotContext | undefined)[] = [];
    const support = whoamiAgent(seen);
    const { fastify, test } = await setup({ agents: [support], context: { headers: ["X-Tenant"] } }, { script }, (f) => authenticate(f));
    fastify.get("/ask", async (request) => request.copilot.agent("support").send({ prompt: "who am I?" }));

    const res = await fastify.inject({ url: "/ask", headers: { "x-user": "alice", "x-tenant": "acme", "x-secret": "s" } });
    expect(res.body).toBe("done");
    expect(test.runtime.toolCalls).toMatchObject([{ name: "whoami", result: "alice" }]);
    expect(seen[0]).toMatchObject({
      principal: "alice",
      attributes: { user: { id: "alice", role: "admin" }, headers: { "x-tenant": "acme" }, requestId: expect.any(String) },
    });
    expect((seen[0]!.attributes.headers as Record<string, string>)["x-secret"]).toBeUndefined();
  });

  it("exposes context and signal on request.copilot and the ALS context in the handler", async () => {
    const { fastify } = await setup({}, {}, (f) => authenticate(f));
    fastify.get("/ctx", async (request) => ({
      principal: request.copilot.context.principal,
      als: getCopilotContext()?.principal,
      signal: request.copilot.signal instanceof AbortSignal,
      aborted: request.copilot.signal?.aborted,
    }));
    const res = await fastify.inject({ url: "/ctx", headers: { "x-user": "bob" } });
    expect(res.json()).toEqual({ principal: "bob", als: "bob", signal: true, aborted: false });
  });

  it("lets explicit context win over the request context", async () => {
    const seen: (CopilotContext | undefined)[] = [];
    const { fastify, test } = await setup({ agents: [whoamiAgent(seen)] }, { script }, (f) => authenticate(f));
    fastify.get("/ask", async (request) =>
      request.copilot.agent("support").send({ prompt: "who?", context: { principal: "system", attributes: { job: "nightly" } } }),
    );
    await fastify.inject({ url: "/ask", headers: { "x-user": "alice" } });
    expect(test.runtime.toolCalls[0]).toMatchObject({ result: "system" });
    expect(seen[0]?.attributes).toMatchObject({ job: "nightly", user: { id: "alice" } });
  });

  it("uses the request principal for conversations", async () => {
    const support = defineAgent({ name: "support" });
    const { fastify, test } = await setup({ agents: [support] }, { script: (rt) => rt.onPrompt(/.*/).reply("ok") }, (f) => authenticate(f));
    fastify.get("/c", async (request) => request.copilot.agent("support").conversation("c1", { mode: "create-or-resume" }).send("hi"));
    await fastify.inject({ url: "/c", headers: { "x-user": "alice" } });
    await fastify.inject({ url: "/c", headers: { "x-user": "bob" } });
    await fastify.inject({ url: "/c", headers: { "x-user": "alice" } });
    expect(new Set(test.runtime.sessionIds).size).toBe(2);
  });

  it("supports custom principal, attributes, and hook stage", async () => {
    const seen: (CopilotContext | undefined)[] = [];
    const { fastify } = await setup(
      {
        agents: [whoamiAgent(seen)],
        context: {
          hook: "onRequest",
          principal: async (request) => request.headers["x-api-key"] as string | undefined,
          attributes: (request) => ({ route: request.url }),
        },
      },
      { script },
    );
    fastify.post("/ask", async (request) => request.copilot.agent("support").send({ prompt: "who?" }));
    const res = await fastify.inject({ method: "POST", url: "/ask", headers: { "x-api-key": "key-1" }, payload: { a: 1 } });
    expect(res.body).toBe("done");
    expect(seen[0]).toMatchObject({ principal: "key-1", attributes: { route: "/ask" } });
  });

  it("can be disabled", async () => {
    const { fastify } = await setup({ context: false }, {}, (f) => authenticate(f));
    fastify.get("/ctx", async (request) => ({ context: request.copilot.context, signal: request.copilot.signal ?? null, als: getCopilotContext() ?? null }));
    const res = await fastify.inject({ url: "/ctx", headers: { "x-user": "bob" } });
    expect(res.json()).toEqual({ context: { attributes: {} }, signal: null, als: null });
  });

  it("returns principal 'sub' claims and skips abort signal when abortOnDisconnect is false", async () => {
    const { fastify } = await setup({ context: { abortOnDisconnect: false } }, {}, (f) => {
      f.decorateRequest("user", null);
      f.addHook("onRequest", async (request) => {
        (request as FastifyRequest & { user: unknown }).user = { sub: "auth0|42" };
      });
    });
    fastify.get("/ctx", async (request) => ({ principal: request.copilot.context.principal, signal: request.copilot.signal ?? null }));
    expect((await fastify.inject("/ctx")).json()).toEqual({ principal: "auth0|42", signal: null });
  });

  it("applies the request signal unless one is passed explicitly", async () => {
    const support = defineAgent({ name: "support" });
    const { fastify } = await setup({ agents: [support] }, { script: (rt) => rt.onPrompt(/.*/).reply("ok") });
    fastify.get("/explicit", async (request) => {
      const controller = new AbortController();
      controller.abort();
      return request.copilot.agent("support").send({ prompt: "x", signal: controller.signal }).catch((e: Error) => e.name);
    });
    expect((await fastify.inject("/explicit")).body).toBe("CopilotAbortedError");
  });
});
