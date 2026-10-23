import {
  CopilotAbortedError,
  CopilotAdmissionRejectedError,
  CopilotConfigurationError,
  CopilotConversationBusyError,
  CopilotConversationExistsError,
  CopilotConversationNotFoundError,
  CopilotIntegrationError,
  CopilotOutputValidationError,
  CopilotRuntimeUnavailableError,
  CopilotSessionError,
  CopilotShuttingDownError,
  CopilotTimeoutError,
  defineAgent,
} from "@copilot-sdk-integrations/agent-core";
import Fastify from "fastify";
import { describe, expect, it } from "vitest";
import { copilotErrorHandler, copilotPlugin } from "../src/index.js";
import { setup, testCopilot } from "./helpers.js";

const cases: [string, Error, number, string, string?][] = [
  ["admission", new CopilotAdmissionRejectedError("Copilot is at capacity", 2.5), 429, "COPILOT_ADMISSION_REJECTED"],
  ["busy", new CopilotConversationBusyError("c1"), 409, "COPILOT_CONVERSATION_BUSY"],
  ["exists", new CopilotConversationExistsError("c1"), 409, "COPILOT_CONVERSATION_EXISTS"],
  ["not-found", new CopilotConversationNotFoundError("c1"), 404, "COPILOT_CONVERSATION_NOT_FOUND"],
  ["timeout", new CopilotTimeoutError(100), 504, "COPILOT_TIMEOUT"],
  ["unavailable", new CopilotRuntimeUnavailableError("runtime down"), 503, "COPILOT_RUNTIME_UNAVAILABLE"],
  ["shutting-down", new CopilotShuttingDownError(), 503, "COPILOT_SHUTTING_DOWN"],
  ["session", new CopilotSessionError("model failed", "model_error"), 502, "COPILOT_SESSION"],
  ["validation", new CopilotOutputValidationError("bad output"), 502, "COPILOT_OUTPUT_VALIDATION"],
  ["configuration", new CopilotConfigurationError("secret config detail"), 500, "COPILOT_CONFIGURATION", "Internal Server Error"],
  ["integration", new CopilotIntegrationError("secret detail"), 500, "COPILOT_INTEGRATION", "Internal Server Error"],
  ["aborted", new CopilotAbortedError(), 499, "COPILOT_ABORTED"],
];

describe("error handler", () => {
  it.each(cases)("maps %s to %i", async (_name, error, status, code, message) => {
    const { fastify } = await setup();
    fastify.get("/", async () => {
      throw error;
    });
    const res = await fastify.inject("/");
    expect(res.statusCode).toBe(status);
    expect(res.json()).toEqual({
      statusCode: status,
      code,
      error: status === 499 ? "Client Closed Request" : expect.any(String),
      message: message ?? error.message,
    });
    if (status === 429) expect(res.headers["retry-after"]).toBe("3");
    else expect(res.headers["retry-after"]).toBeUndefined();
  });

  it("maps a real admission rejection with Retry-After", async () => {
    const support = defineAgent({ name: "support" });
    const { fastify } = await setup(
      { agents: [support] },
      { limits: { maxConcurrentTurns: 1, maxQueuedTurns: 0 }, script: (rt) => rt.onPrompt(/.*/).delay(200).reply("ok") },
    );
    fastify.get("/", async (request) => request.copilot.agent("support").send({ prompt: "x" }));
    const [a, b] = await Promise.all([fastify.inject("/"), fastify.inject("/")]);
    const rejected = [a, b].find((res) => res.statusCode === 429)!;
    expect([a, b].map((r) => r.statusCode).sort()).toEqual([200, 429]);
    expect(rejected.headers["retry-after"]).toMatch(/^\d+$/);
    expect(rejected.json()).toMatchObject({ statusCode: 429, code: "COPILOT_ADMISSION_REJECTED", error: "Too Many Requests" });
  });

  it("maps a failed lazy start to 503", async () => {
    const support = defineAgent({ name: "support" });
    const { fastify } = await setup({ agents: [support] }, { script: (rt) => rt.failStart("no runtime") });
    fastify.get("/", async (request) => request.copilot.agent("support").send({ prompt: "x" }));
    const res = await fastify.inject("/");
    expect(res.statusCode).toBe(503);
    expect(res.json()).toMatchObject({ code: "COPILOT_RUNTIME_UNAVAILABLE", error: "Service Unavailable" });
  });

  it("leaves validation errors and other errors to Fastify's default handler", async () => {
    const { fastify } = await setup();
    fastify.post("/v", { schema: { body: { type: "object", required: ["name"], properties: { name: { type: "string" } } } } }, async () => "ok");
    fastify.get("/boom", async () => {
      throw new Error("boom");
    });
    fastify.get("/teapot", async () => {
      throw Object.assign(new Error("short and stout"), { statusCode: 418 });
    });
    const validation = await fastify.inject({ method: "POST", url: "/v", payload: {} });
    expect(validation.statusCode).toBe(400);
    expect(validation.json()).toMatchObject({ statusCode: 400, code: "FST_ERR_VALIDATION", error: "Bad Request" });
    const boom = await fastify.inject("/boom");
    expect(boom.statusCode).toBe(500);
    expect(boom.json()).toEqual({ statusCode: 500, error: "Internal Server Error", message: "boom" });
    const teapot = await fastify.inject("/teapot");
    expect(teapot.statusCode).toBe(418);
    expect(teapot.json()).toMatchObject({ statusCode: 418, message: "short and stout" });
  });

  it("is skipped with errorHandler: false, and usable from the app's own handler", async () => {
    const { fastify } = await setup({ errorHandler: false });
    fastify.get("/", async () => {
      throw new CopilotTimeoutError(5);
    });
    expect((await fastify.inject("/")).statusCode).toBe(500);

    const { copilot } = testCopilot();
    const custom = Fastify();
    await custom.register(copilotPlugin, { client: copilot, errorHandler: false });
    custom.setErrorHandler((error, request, reply) => {
      if (error instanceof CopilotIntegrationError) return copilotErrorHandler(error, request, reply);
      return reply.code(500).send({ custom: true });
    });
    custom.get("/copilot", async () => {
      throw new CopilotTimeoutError(5);
    });
    custom.get("/other", async () => {
      throw new Error("x");
    });
    expect((await custom.inject("/copilot")).statusCode).toBe(504);
    expect((await custom.inject("/other")).json()).toEqual({ custom: true });
    await custom.close();
  });
});
