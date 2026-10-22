import type { CopilotApprovalStore } from "@copilot-sdk-integrations/ai";
import { getCopilotContext, type Copilot, type CopilotOptions } from "@copilot-sdk-integrations/core";
import { copilotPlugin } from "@copilot-sdk-integrations/fastify";
import { copilotAiPlugin, type CopilotChatBody } from "@copilot-sdk-integrations/fastify/ai";
import Fastify, { type FastifyInstance, type FastifyRequest, type FastifyServerOptions } from "fastify";
import { createAdvisor, Notebook } from "./advisor.js";

declare module "@copilot-sdk-integrations/fastify" {
  interface CopilotAgentRegistry {
    "node-advisor": ReturnType<typeof createAdvisor>;
  }
}

export interface AppOptions {
  /** Core client options, or an existing `Copilot` (tests pass a scripted one). */
  client?: CopilotOptions | Copilot;
  notebook?: Notebook;
  approvals?: CopilotApprovalStore;
  logger?: FastifyServerOptions["logger"];
}

/** Demo identity: the caller names itself with `x-user-id`. Use real authentication in production. */
function userOf(request: FastifyRequest): string {
  const header = request.headers["x-user-id"];
  const id = (Array.isArray(header) ? header[0] : header)?.trim();
  return id ? id : "anonymous";
}

const questionSchema = {
  type: "object",
  required: ["question"],
  properties: { question: { type: "string", minLength: 1, pattern: "\\S" } },
} as const;

const promptQuerySchema = {
  type: "object",
  required: ["q"],
  properties: { q: { type: "string", minLength: 1, pattern: "\\S" } },
} as const;

const chatSchema = {
  type: "object",
  properties: { id: { type: "string" }, messages: { type: "array", items: { type: "object" } } },
} as const;

export async function buildApp({ client = {}, notebook = new Notebook(), approvals, logger = false }: AppOptions = {}): Promise<FastifyInstance> {
  const app = Fastify({ logger });

  // Tools read the caller from the turn's context, which the plugin captures from each request.
  const advisor = createAdvisor(notebook, () => getCopilotContext()?.principal ?? "anonymous");

  await app.register(copilotPlugin, {
    client,
    agents: [advisor],
    context: { principal: userOf },
    health: { path: "/health" },
  });
  await app.register(copilotAiPlugin, approvals ? { approvals } : {});

  // AI SDK `useChat` endpoint: history stays server-side (keyed by `${user}:${chat id}`), approvals round-trip.
  app.post<{ Body: CopilotChatBody }>("/api/chat", { schema: { body: chatSchema } }, async (_request, reply) =>
    reply.copilotChat({ agent: "node-advisor" }),
  );

  // One-shot question streamed as an AI SDK UI message stream (SSE).
  app.get<{ Querystring: { q: string } }>("/api/stream", { schema: { querystring: promptQuerySchema } }, async (request, reply) =>
    reply.copilotUIStream(request.copilot.agent("node-advisor").stream({ prompt: request.query.q.trim() })),
  );

  // The same question as raw Copilot events over SSE (`event: <type>` + `data: <json>`).
  app.get<{ Querystring: { q: string } }>("/api/events", { schema: { querystring: promptQuerySchema } }, async (request, reply) =>
    reply.copilotEvents(request.copilot.agent("node-advisor").stream({ prompt: request.query.q.trim() })),
  );

  // Non-streaming request/response. Copilot errors map to 429, 409, 404, 504, 499, 503, or 502.
  app.post<{ Body: { question: string } }>("/api/ask", { schema: { body: questionSchema } }, async (request) => ({
    answer: await request.copilot.agent("node-advisor").send({ prompt: request.body.question.trim() }),
  }));

  app.get("/api/notes", async (request) => notebook.list(userOf(request)));

  return app;
}
