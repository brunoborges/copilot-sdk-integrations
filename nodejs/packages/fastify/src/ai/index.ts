import {
  createApprovalStore,
  createCopilotChatResponse,
  toUIMessageStreamResponse,
  type CopilotApprovalStore,
  type StreamCopilotChatOptions,
  type ToUIMessageStreamOptions,
  type UIMessageStreamResponseInit,
} from "@copilot-sdk-integrations/ai";
import type { AgentHandle, CopilotAgentEvent } from "@copilot-sdk-integrations/core";
import type { UIMessage } from "ai";
import type { FastifyInstance, FastifyReply } from "fastify";
import fp from "fastify-plugin";
import { bindAgentHandle } from "../context.js";
import { PLUGIN_NAME } from "../plugin.js";
import type { CopilotAgentName } from "../types.js";

export const AI_PLUGIN_NAME = `${PLUGIN_NAME}/ai`;

export interface CopilotAiPluginOptions {
  /** Where suspended turns wait for tool approvals. Default: a new in-memory store for this app. */
  approvals?: CopilotApprovalStore;
}

/** The `useChat` request body. */
export interface CopilotChatBody {
  /** The `useChat` chat id; scoped by principal into the conversation id. */
  id?: string;
  messages?: UIMessage[];
}

export interface CopilotChatOptions
  extends Omit<StreamCopilotChatOptions, "agent" | "messages" | "conversationId" | "approvals" | "signal">,
    UIMessageStreamResponseInit {
  /** A registered agent name or an agent handle (bound to the request's context and signal). */
  agent: CopilotAgentName | AgentHandle;
  /** The `useChat` request body. Default: `request.body`. */
  request?: CopilotChatBody;
  /** Conversation key. Default: `${principal}:${request.id}` (principal `"anonymous"` without one); ephemeral without a chat id. */
  conversationId?: string;
  /** Default: `fastify.copilotApprovals`. */
  approvals?: CopilotApprovalStore;
  /** Default: `request.copilot.signal` (client disconnect). */
  signal?: AbortSignal;
}

export type CopilotUIStreamOptions = ToUIMessageStreamOptions & UIMessageStreamResponseInit;

declare module "fastify" {
  interface FastifyInstance {
    /** The app's approval store for `reply.copilotChat()` (in-memory: route a conversation to one replica). */
    copilotApprovals: CopilotApprovalStore;
  }
  interface FastifyReply {
    /** Answers a `useChat` request: runs the turn and streams the AI SDK UI message stream, with tool approvals. */
    copilotChat(options: CopilotChatOptions): FastifyReply;
    /** Streams Copilot events as an AI SDK UI message stream. */
    copilotUIStream(events: AsyncIterable<CopilotAgentEvent>, options?: CopilotUIStreamOptions): FastifyReply;
  }
}

/** Sends a web `Response` (status, headers, body) through a Fastify reply. */
export function sendWebResponse(reply: FastifyReply, response: Response): FastifyReply {
  reply.code(response.status);
  response.headers.forEach((value, name) => {
    reply.header(name, value);
  });
  return reply.send(response.body);
}

async function copilotAi(fastify: FastifyInstance, options: CopilotAiPluginOptions) {
  fastify.decorate("copilotApprovals", options.approvals ?? createApprovalStore());

  fastify.decorateReply("copilotChat", function (this: FastifyReply, chatOptions: CopilotChatOptions) {
    const { agent, request: body = (this.request.body ?? {}) as CopilotChatBody, conversationId, approvals, signal, ...rest } = chatOptions;
    const requestCopilot = this.request.copilot;
    const handle =
      typeof agent === "string"
        ? requestCopilot.agent(agent as CopilotAgentName)
        : bindAgentHandle(agent, () => requestCopilot.context, () => requestCopilot.signal);
    const principal = requestCopilot.context.principal ?? "anonymous";
    const chatId = typeof body.id === "string" && body.id !== "" ? body.id : undefined;
    const effectiveConversationId = conversationId ?? (chatId === undefined ? undefined : `${principal}:${chatId}`);
    const effectiveSignal = signal ?? requestCopilot.signal;
    // The principal also records who may answer the turn's approvals, so pass it explicitly.
    const requestPrincipal = requestCopilot.context.principal;
    const context = requestPrincipal === undefined || rest.context?.principal !== undefined ? rest.context : { ...rest.context, principal: requestPrincipal };
    const response = createCopilotChatResponse({
      ...rest,
      agent: handle,
      messages: Array.isArray(body.messages) ? body.messages : [],
      approvals: approvals ?? fastify.copilotApprovals,
      ...(effectiveConversationId === undefined ? {} : { conversationId: effectiveConversationId }),
      ...(effectiveSignal === undefined ? {} : { signal: effectiveSignal }),
      ...(context === undefined ? {} : { context }),
    });
    return sendWebResponse(this, response);
  });

  fastify.decorateReply("copilotUIStream", function (this: FastifyReply, events: AsyncIterable<CopilotAgentEvent>, streamOptions: CopilotUIStreamOptions = {}) {
    return sendWebResponse(this, toUIMessageStreamResponse(events, streamOptions));
  });
}

/**
 * Adds `reply.copilotChat()`, `reply.copilotUIStream()`, and `fastify.copilotApprovals` (AI SDK
 * `useChat` support). Register after `copilotPlugin`.
 */
export const copilotAiPlugin = fp(copilotAi, { name: AI_PLUGIN_NAME, fastify: "5.x", dependencies: [PLUGIN_NAME] });
