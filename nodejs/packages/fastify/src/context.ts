import {
  runWithCopilotContext,
  type AgentHandle,
  type BoundConversation,
  type CopilotAgentDefinition,
  type CopilotContext,
  type SendOptions,
} from "@copilot-sdk-integrations/agent-core";
import type { FastifyInstance, FastifyReply, FastifyRequest, HookHandlerDoneFunction } from "fastify";
import type { CopilotContextOptions, FastifyCopilot, RequestCopilot } from "./types.js";

const kContext = Symbol("copilot.context");
const kSignal = Symbol("copilot.signal");
const kRequestCopilot = Symbol("copilot.request");

interface RequestState {
  [kContext]?: CopilotContext;
  [kSignal]?: AbortSignal;
  [kRequestCopilot]?: RequestCopilot;
}

/** Default principal: `request.user` as a string, or its `id` or `sub`. */
export function defaultPrincipal(request: FastifyRequest): string | undefined {
  const user = (request as { user?: unknown }).user;
  if (typeof user === "string") return user || undefined;
  if (!user || typeof user !== "object") return undefined;
  for (const key of ["id", "sub"]) {
    const value = (user as Record<string, unknown>)[key];
    if (typeof value === "string" && value !== "") return value;
    if (typeof value === "number") return String(value);
  }
  return undefined;
}

/** Builds the `CopilotContext` of a request (principal, request id, user, selected headers, attributes). */
export async function copilotContextFromRequest(request: FastifyRequest, options: CopilotContextOptions = {}): Promise<CopilotContext> {
  const context: CopilotContext = { attributes: {} };
  const principal = options.principal ? await options.principal(request) : defaultPrincipal(request);
  if (principal !== undefined) context.principal = principal;
  const attributes: Record<string, unknown> = { requestId: request.id };
  const user = (request as { user?: unknown }).user;
  if (user !== undefined) attributes.user = user;
  if (options.headers?.length) {
    const headers: Record<string, string> = {};
    for (const name of options.headers) {
      const value = request.headers[name.toLowerCase()];
      if (value !== undefined) headers[name.toLowerCase()] = Array.isArray(value) ? value.join(", ") : String(value);
    }
    attributes.headers = headers;
  }
  Object.assign(attributes, (await options.attributes?.(request)) ?? {});
  context.attributes = attributes;
  return context;
}

/** An abort signal that fires when the client goes away before the response finishes. */
function disconnectSignal(request: FastifyRequest, reply: FastifyReply): AbortSignal {
  const controller = new AbortController();
  const req = request.raw;
  const res = reply.raw;
  const abort = () => {
    if (!res.writableEnded && !controller.signal.aborted) controller.abort();
  };
  // `request.raw` closes once its body is consumed; only a destroyed socket or an errored or
  // incomplete request means the client left. The response closing before it ended always does.
  const onRequestClose = () => {
    if (req.socket?.destroyed || (req as { errored?: unknown }).errored || req.complete === false) abort();
  };
  if (res.destroyed || req.socket?.destroyed) abort();
  req.once("close", onRequestClose);
  res.once("close", abort);
  return controller.signal;
}

/** Adds the hook that captures the request context and runs the rest of the request inside it. */
export function addContextHook(fastify: FastifyInstance, options: CopilotContextOptions): void {
  const stage = options.hook ?? "preHandler";
  const abortOnDisconnect = options.abortOnDisconnect !== false;
  fastify.addHook(stage, (request: FastifyRequest, reply: FastifyReply, done: HookHandlerDoneFunction) => {
    copilotContextFromRequest(request, options).then(
      (context) => {
        const state = request as unknown as RequestState;
        if (abortOnDisconnect) {
          const signal = disconnectSignal(request, reply);
          context.signal = signal;
          state[kSignal] = signal;
        }
        state[kContext] = context;
        runWithCopilotContext(context, () => done());
      },
      (error: Error) => done(error),
    );
  });
  if (stage !== "preHandler") {
    // Body parsing runs outside the async scope of earlier hooks; re-enter it before the handler.
    fastify.addHook("preHandler", (request: FastifyRequest, _reply: FastifyReply, done: HookHandlerDoneFunction) => {
      const context = (request as unknown as RequestState)[kContext];
      if (context) runWithCopilotContext(context, () => done());
      else done();
    });
  }
}

/** Decorates `request.copilot` with a lazy getter. */
export function decorateRequestCopilot(fastify: FastifyInstance): void {
  fastify.decorateRequest("copilot", {
    getter(this: FastifyRequest) {
      const state = this as unknown as RequestState;
      return (state[kRequestCopilot] ??= createRequestCopilot(this, fastify.copilot));
    },
  });
}

function createRequestCopilot(request: FastifyRequest, copilot: FastifyCopilot): RequestCopilot {
  const state = request as unknown as RequestState;
  const handles = new Map<string | CopilotAgentDefinition, AgentHandle>();
  const requestCopilot: RequestCopilot = {
    agent(nameOrDefinition: string | CopilotAgentDefinition) {
      let handle = handles.get(nameOrDefinition);
      if (!handle) {
        const base = copilot.agent(nameOrDefinition as never);
        handle = bindAgentHandle(base, () => state[kContext], () => state[kSignal]);
        handles.set(nameOrDefinition, handle);
      }
      return handle;
    },
    get context() {
      return state[kContext] ?? { attributes: {} };
    },
    get signal() {
      return state[kSignal];
    },
  };
  return requestCopilot;
}

type AnySendOptions = Partial<Pick<SendOptions<unknown>, "context" | "signal">>;

/** Wraps a handle so calls default `context` and `signal` (explicit arguments win). */
export function bindAgentHandle(
  handle: AgentHandle,
  context: () => CopilotContext | undefined,
  signal: () => AbortSignal | undefined,
): AgentHandle {
  const withDefaults = <T extends AnySendOptions>(options: T): T => {
    const result: AnySendOptions = { ...options };
    const base = context();
    if (base) {
      const { signal: _signal, ...rest } = base;
      const explicit = options.context;
      result.context = explicit
        ? { ...rest, ...explicit, attributes: { ...rest.attributes, ...(explicit.attributes ?? {}) } }
        : rest;
    }
    const requestSignal = signal();
    if (options.signal === undefined && requestSignal) result.signal = requestSignal;
    return result as T;
  };

  return {
    definition: handle.definition,
    send: ((options: SendOptions<unknown>) => handle.send(withDefaults(options as AnySendOptions) as never)) as AgentHandle["send"],
    stream: (options) => handle.stream(withDefaults(options as AnySendOptions) as typeof options),
    conversation(conversationId, options = {}) {
      const principal = options.principal ?? context()?.principal;
      const bound = handle.conversation(conversationId, principal === undefined ? options : { ...options, principal });
      const conversation: BoundConversation = {
        conversationId: bound.conversationId,
        send: ((prompt: string, sendOptions: AnySendOptions = {}) => bound.send(prompt, withDefaults(sendOptions) as never)) as BoundConversation["send"],
        stream: (prompt, streamOptions = {}) => bound.stream(prompt, withDefaults(streamOptions as AnySendOptions) as typeof streamOptions),
      };
      return conversation;
    },
    deleteConversation(conversationId, options = {}) {
      const principal = options.principal ?? context()?.principal;
      return handle.deleteConversation(conversationId, principal === undefined ? options : { ...options, principal });
    },
  };
}
