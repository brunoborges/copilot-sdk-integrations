import { copilotErrorToHttp, CopilotIntegrationError, getCopilotContext, type Copilot } from "@copilot-sdk-integrations/agent-core";
import { createApprovalStore, pipeCopilotChatToResponse, pipeCopilotEventsToResponse, type CopilotApprovalStore } from "@copilot-sdk-integrations/ai";
import type { UIMessage } from "ai";
import express, { type NextFunction, type Request, type Response } from "express";
import { createAdvisor, Notebook } from "./advisor.js";

export interface AppOptions {
  copilot: Copilot;
  notebook?: Notebook;
  approvals?: CopilotApprovalStore;
}

/** Demo identity: the caller names itself with `x-user-id`. Use real authentication in production. */
function userOf(req: Request): string {
  const header = req.header("x-user-id")?.trim();
  return header ? header : "anonymous";
}

export function createApp({ copilot, notebook = new Notebook(), approvals = createApprovalStore() }: AppOptions): express.Express {
  // Tools read the caller from the turn's context, which carries the principal passed below.
  const advisor = copilot.agent(createAdvisor(notebook, () => getCopilotContext()?.principal ?? "anonymous"));
  const app = express();
  app.use(express.json());

  // AI SDK `useChat` endpoint: history stays server-side (keyed by chat id), approvals round-trip.
  app.post("/api/chat", async (req: Request<Record<string, string>, unknown, { id?: string; messages?: UIMessage[] }>, res) => {
    const principal = userOf(req);
    const controller = new AbortController();
    res.on("close", () => {
      if (!res.writableEnded) controller.abort();
    });
    await pipeCopilotChatToResponse(res, {
      agent: advisor,
      messages: req.body.messages ?? [],
      ...(req.body.id ? { conversationId: `${principal}:${req.body.id}` } : {}),
      context: { principal },
      approvals,
      signal: controller.signal,
    });
  });

  // One-shot question streamed as an AI SDK UI message stream (SSE).
  app.get("/api/stream", async (req, res) => {
    const prompt = String(req.query.q ?? "").trim();
    if (!prompt) {
      res.status(400).json({ error: "Missing q" });
      return;
    }
    const controller = new AbortController();
    res.on("close", () => {
      if (!res.writableEnded) controller.abort();
    });
    await pipeCopilotEventsToResponse(res, advisor.stream({ prompt, context: { principal: userOf(req) }, signal: controller.signal }));
  });

  app.post("/api/ask", async (req: Request<Record<string, string>, unknown, { question?: string }>, res, next) => {
    try {
      const question = req.body.question?.trim();
      if (!question) {
        res.status(400).json({ error: "Missing question" });
        return;
      }
      res.json({ answer: await advisor.send({ prompt: question, context: { principal: userOf(req) } }) });
    } catch (error) {
      next(error);
    }
  });

  app.get("/api/notes", (req, res) => {
    res.json(notebook.list(userOf(req)));
  });

  app.get("/health", async (_req, res) => {
    const health = await copilot.health();
    res.status(["stopping", "stopped", "failed"].includes(health.state) ? 503 : 200).json(health);
  });

  // Copilot errors map to 429 (with Retry-After), 409, 404, 504, 499, 503, or 502; anything else is a 500.
  app.use((error: Error, _req: Request, res: Response, _next: NextFunction) => {
    const { status, headers } = error instanceof CopilotIntegrationError ? copilotErrorToHttp(error) : { status: 500, headers: {} };
    if (status >= 500) console.error(error);
    if (res.headersSent) {
      res.end();
      return;
    }
    res.set(headers).status(status).json({ error: status === 500 ? "Copilot request failed" : error.message });
  });

  return app;
}
