import { randomUUID } from "node:crypto";
import type { MCPServerConfig, ResumeSessionConfig, SessionConfig, SessionEvent, SessionHooks, SystemMessageConfig } from "@github/copilot-sdk";
import type { CopilotRuntime } from "./client.js";
import { bindCopilotContext, getCopilotContext, runWithCopilotContext } from "./context.js";
import { abortReason, deriveSessionId } from "./conversation.js";
import { defineAgent } from "./define-agent.js";
import {
  CopilotAbortedError,
  CopilotConfigurationError,
  CopilotConversationBusyError,
  CopilotConversationExistsError,
  CopilotConversationNotFoundError,
  CopilotIntegrationError,
  CopilotOutputValidationError,
  CopilotSessionError,
  CopilotTimeoutError,
} from "./errors.js";
import { createEventMappingState, mapSessionEvent } from "./events.js";
import { buildToolFilterConfig, composeHooks, evaluatePermission, toPermissionResult } from "./governance/index.js";
import { toJsonSchema, toSdkTools } from "./tools/index.js";
import type {
  AgentHandle,
  BoundConversation,
  ClientLike,
  ContextualStringMap,
  ConversationMode,
  CopilotAgentDefinition,
  CopilotMCPServerConfig,
  CopilotAgentEvent,
  CopilotContext,
  Instructions,
  PermissionRequest,
  PermissionRequestResult,
  PermissionResponse,
  SendOptions,
  SessionLike,
  StructuredSchema,
  ToolInput,
} from "./types.js";

/** Extra lease time beyond the turn timeout, so a lease outlives its turn's cleanup. */
const LEASE_MARGIN_MS = 30_000;

/** Store key for a conversation's cross-replica lease. */
export function conversationLeaseKey(sessionId: string): string {
  return `copilot:conversation:${sessionId}`;
}

interface TurnRequest {
  prompt: string;
  conversationId?: string;
  mode: ConversationMode;
  /** Explicit principal (bound conversations); wins over `context.principal` and the ambient context. */
  principal?: string;
  schema?: StructuredSchema<unknown>;
  signal?: AbortSignal;
  timeout?: number;
  tools?: ToolInput[];
  context?: Partial<CopilotContext>;
  onPermissionRequest?: SendOptions["onPermissionRequest"];
  streaming: boolean;
  /** Stream consumer (only for `stream()`). */
  sink?: (event: CopilotAgentEvent) => void;
}

interface TurnResult {
  text: string;
  value?: unknown;
  sessionId: string;
}

/** Creates the executor behind `copilot.agent(def)`. */
export function createAgentHandle(runtime: CopilotRuntime, input: CopilotAgentDefinition): AgentHandle {
  const definition = defineAgent(input);

  const send = async (options: SendOptions<unknown>, overrides: Partial<TurnRequest> = {}): Promise<unknown> => {
    const result = await runTurn(runtime, definition, {
      ...toTurnRequest(options),
      streaming: false,
      ...overrides,
    });
    return options.schema ? result.value : result.text;
  };

  const stream = (options: Omit<SendOptions, "schema">, overrides: Partial<TurnRequest> = {}) =>
    streamTurn((sink, signal) =>
      runTurn(runtime, definition, {
        ...toTurnRequest(options),
        streaming: true,
        ...overrides,
        signal,
        sink,
      }),
      options.signal,
    );

  const handle: AgentHandle = {
    definition,
    send: ((options: SendOptions<unknown>) => send(options)) as AgentHandle["send"],
    stream: (options) => stream(options),
    conversation(conversationId, options = {}) {
      requireNonBlank("conversationId", conversationId);
      const mode = options.mode ?? "resume-only";
      const overrides: Partial<TurnRequest> = { mode };
      if (options.principal !== undefined) overrides.principal = options.principal;
      const bound: BoundConversation = {
        conversationId,
        send: ((prompt: string, sendOptions: Omit<SendOptions<unknown>, "prompt" | "conversationId"> = {}) =>
          send({ ...sendOptions, prompt, conversationId }, overrides)) as BoundConversation["send"],
        stream: (prompt, streamOptions = {}) => stream({ ...streamOptions, prompt, conversationId }, overrides),
      };
      return bound;
    },
    async deleteConversation(conversationId, options = {}) {
      requireNonBlank("conversationId", conversationId);
      const principal = options.principal ?? getCopilotContext()?.principal;
      const sessionId = deriveSessionId(definition.name, principal, conversationId);
      const release = await runtime.locks.acquire(sessionId, { waitMs: runtime.conversationWaitMs, conversationId });
      let releaseLease: (() => Promise<void>) | undefined;
      try {
        const store = runtime.options.store;
        if (store) {
          releaseLease = await store.acquireLease(
            conversationLeaseKey(sessionId),
            runtime.responseTimeout + LEASE_MARGIN_MS,
            runtime.conversationWaitMs,
          );
          if (!releaseLease) throw new CopilotConversationBusyError(conversationId);
        }
        const client = await runtime.acquireClient();
        if (client.getSessionMetadata && (await client.getSessionMetadata(sessionId)) == null) return;
        try {
          await client.deleteSession(sessionId);
        } catch (error) {
          if (!isNotFoundError(error)) throw error;
        }
      } finally {
        if (releaseLease) await releaseLease().catch((e) => runtime.logger.warn("Failed to release lease", { error: messageOf(e) }));
        release();
      }
    },
  };
  return handle;
}

function toTurnRequest(options: SendOptions<unknown>): Omit<TurnRequest, "streaming"> {
  if (!options || typeof options !== "object") throw new TypeError("send options must be an object");
  return {
    prompt: options.prompt,
    conversationId: options.conversationId,
    mode: "create-or-resume",
    schema: options.schema,
    signal: options.signal,
    timeout: options.timeout,
    tools: options.tools,
    context: options.context,
    onPermissionRequest: options.onPermissionRequest,
  };
}

function requireNonBlank(name: string, value: unknown): asserts value is string {
  if (typeof value !== "string" || value.trim() === "") {
    throw new TypeError(`${name} must be a non-blank string`);
  }
}

// ---------------------------------------------------------------------------
// Turn execution
// ---------------------------------------------------------------------------

async function runTurn(runtime: CopilotRuntime, def: CopilotAgentDefinition, req: TurnRequest): Promise<TurnResult> {
  requireNonBlank("prompt", req.prompt);
  if (req.conversationId !== undefined) requireNonBlank("conversationId", req.conversationId);
  const timeoutMs = req.timeout ?? runtime.responseTimeout;
  if (typeof timeoutMs !== "number" || Number.isNaN(timeoutMs) || timeoutMs <= 0) {
    throw new TypeError("timeout must be a positive number of milliseconds");
  }

  const ambient = getCopilotContext();
  const principal = req.principal ?? req.context?.principal ?? ambient?.principal;
  const conversationId = req.conversationId;
  const ephemeral = conversationId === undefined;
  const sessionId = ephemeral
    ? `copilot-${def.name}-${randomUUID()}`
    : deriveSessionId(def.name, principal, conversationId);

  const emittedErrors = new WeakSet<Error>();
  let turnContext: CopilotContext | undefined;
  const emit = (event: CopilotAgentEvent) => {
    if (event.type === "error") emittedErrors.add(event.error);
    if (req.sink) {
      try {
        req.sink(event);
      } catch (error) {
        runtime.logger.warn("Copilot stream sink failed", { error: messageOf(error) });
      }
    }
    deliverToListener(runtime, event, turnContext ?? { ...ambient, agentName: def.name, attributes: {} });
  };

  const controller = new AbortController();
  const deadline = Date.now() + timeoutMs;
  const timer = setTimeout(() => controller.abort(new CopilotTimeoutError(timeoutMs)), timeoutMs);
  const onCallerAbort = () => controller.abort(callerAbortReason(req.signal!));
  if (req.signal?.aborted) onCallerAbort();
  else req.signal?.addEventListener("abort", onCallerAbort, { once: true });
  const signal = controller.signal;

  let ticket: ReturnType<CopilotRuntime["admission"]["reserve"]> | undefined;
  let releaseLock: (() => void) | undefined;
  let releaseLease: (() => Promise<void>) | undefined;
  let unregister: (() => void) | undefined;
  try {
    if (signal.aborted) throw abortReason(signal);
    ticket = runtime.admission.reserve();
    if (!ephemeral) {
      releaseLock = await runtime.locks.acquire(sessionId, {
        waitMs: runtime.conversationWaitMs,
        signal,
        conversationId,
      });
      const store = runtime.options.store;
      if (store) {
        const lease = await raceAbort(
          store.acquireLease(conversationLeaseKey(sessionId), timeoutMs + LEASE_MARGIN_MS, runtime.conversationWaitMs),
          signal,
          (late) => void late?.().catch(() => undefined),
        );
        if (!lease) throw new CopilotConversationBusyError(conversationId);
        releaseLease = lease;
      }
    }
    await ticket.acquire(signal);
    unregister = runtime.registerTurn(controller);

    const baseContext: Partial<CopilotContext> = {
      ...req.context,
      principal,
      conversationId,
      agentName: def.name,
      sessionId,
      signal,
      attributes: { ...(req.context?.attributes ?? {}) },
    };
    return await runWithCopilotContext(baseContext, async () => {
      let githubToken = req.context?.githubToken;
      if (githubToken === undefined && runtime.options.tokenResolver) {
        githubToken = await raceAbort(Promise.resolve(runtime.options.tokenResolver(getCopilotContext()!)), signal);
      }
      const run = () => {
        turnContext = getCopilotContext()!;
        return executeTurn(runtime, def, req, { sessionId, ephemeral, signal, controller, deadline, emit });
      };
      return githubToken === undefined ? run() : runWithCopilotContext({ githubToken }, run);
    });
  } catch (error) {
    const failure = signal.aborted ? abortReason(signal) : error;
    if (failure instanceof Error && !emittedErrors.has(failure)) emit({ type: "error", error: failure });
    throw failure;
  } finally {
    clearTimeout(timer);
    req.signal?.removeEventListener("abort", onCallerAbort);
    unregister?.();
    ticket?.release();
    if (releaseLease) {
      await releaseLease().catch((e) => runtime.logger.warn("Failed to release conversation lease", { error: messageOf(e) }));
    }
    releaseLock?.();
  }
}

function callerAbortReason(signal: AbortSignal): Error {
  const reason: unknown = signal.reason;
  return reason instanceof CopilotIntegrationError ? reason : new CopilotAbortedError();
}

interface TurnRuntime {
  sessionId: string;
  ephemeral: boolean;
  signal: AbortSignal;
  controller: AbortController;
  deadline: number;
  emit(event: CopilotAgentEvent): void;
}

async function executeTurn(
  runtime: CopilotRuntime,
  def: CopilotAgentDefinition,
  req: TurnRequest,
  turn: TurnRuntime,
): Promise<TurnResult> {
  const { sessionId, signal, emit } = turn;
  const context = getCopilotContext()!;
  const client = await raceAbort(runtime.acquireClient(turn.controller), signal);

  const { tools, customToolNames, approvalRequired } = toSdkTools([...(def.tools ?? []), ...(req.tools ?? [])], {
    getContext: () => context,
  });

  const askUser = async (request: PermissionRequest): Promise<PermissionRequestResult> => {
    if (req.streaming) {
      const requestId = randomUUID();
      let respond!: (response: PermissionResponse) => void;
      const answered = new Promise<PermissionResponse>((resolve) => {
        let done = false;
        respond = (response) => {
          if (done) return;
          done = true;
          resolve(response && typeof response === "object" ? response : { approved: false });
        };
      });
      emit({ type: "permission-request", requestId, request, respond: (r) => respond(r) });
      const response = await withWindow(answered, runtime.approvalWindow, signal);
      emit({ type: "permission-resolved", requestId, approved: response?.approved === true });
      if (!response) return toPermissionResult("user-not-available");
      return response.approved === true ? toPermissionResult("approve") : toPermissionResult("deny", response.feedback);
    }
    if (!req.onPermissionRequest) return toPermissionResult("deny");
    const response = await withWindow(Promise.resolve(req.onPermissionRequest(request)), runtime.approvalWindow, signal);
    if (!response) return toPermissionResult("user-not-available");
    return response.approved === true ? toPermissionResult("approve") : toPermissionResult("deny", response.feedback);
  };

  const onPermissionRequest = bindCopilotContext(async (request: PermissionRequest): Promise<PermissionRequestResult> => {
    try {
      let decision = await evaluatePermission(def.permissions, request, { agent: def, customToolNames, context });
      if (decision === "approve" && request.kind === "custom-tool") {
        const requirement = approvalRequired.get(request.toolName);
        const needsApproval =
          typeof requirement === "function"
            ? await requirement(request.args, { toolCallId: request.toolCallId })
            : requirement === true;
        if (needsApproval) decision = "ask-user";
      }
      if (decision === "approve") return toPermissionResult("approve");
      if (decision === "ask-user") return await askUser(request);
      return toPermissionResult("deny");
    } catch (error) {
      runtime.logger.warn("Copilot permission handler failed; denying", {
        agent: def.name,
        kind: request?.kind,
        error: messageOf(error),
      });
      return toPermissionResult("deny");
    }
  });

  const filters = buildToolFilterConfig(def);
  if (filters.availableTools === undefined) {
    // The SDK's "empty" mode requires an explicit allow-list on every session.
    filters.availableTools = def.builtInTools === true ? ["builtin:*", "custom:*", "mcp:*"] : ["custom:*", "mcp:*"];
  }
  let config: SessionConfig = {
    sessionId,
    tools,
    ...filters,
    onPermissionRequest,
  };
  if (def.model !== undefined) config.model = def.model;
  if (def.reasoningEffort !== undefined) config.reasoningEffort = def.reasoningEffort;
  if (def.provider !== undefined) config.provider = def.provider;
  const systemMessage = toSystemMessage(def.instructions);
  if (systemMessage !== undefined) config.systemMessage = systemMessage;
  if (def.mcpServers !== undefined) config.mcpServers = await raceAbort(resolveMcpServers(def.mcpServers, context), signal);
  if (def.customAgents !== undefined) config.customAgents = def.customAgents;
  if (def.infiniteSessions !== undefined) config.infiniteSessions = { enabled: def.infiniteSessions };
  if (def.toolSearch !== undefined) config.toolSearch = { ...def.toolSearch };
  const hooks = bindHooks(composeHooks(runtime.options.hooks, def.hooks));
  if (hooks !== undefined) config.hooks = hooks;
  if (req.streaming) config.streaming = true;
  if (def.configureSession) {
    config = def.configureSession(config, context) ?? config;
    if (config.sessionId !== sessionId) {
      throw new CopilotConfigurationError(`Agent "${def.name}": configureSession must not change the session id`);
    }
    if (typeof config.onPermissionRequest !== "function") {
      throw new CopilotConfigurationError(`Agent "${def.name}": configureSession must not remove the permission handler`);
    }
    if (config.onPermissionRequest !== onPermissionRequest) {
      config.onPermissionRequest = bindCopilotContext(config.onPermissionRequest);
    }
  }
  const sessionToken = context.githubToken ?? (runtime.options.cliUrl !== undefined ? runtime.options.githubToken : undefined);
  if (sessionToken !== undefined && config.gitHubToken === undefined && config.gitHubTokenProvider === undefined) {
    config.gitHubToken = sessionToken;
  }

  const session = await openSession(client, req, turn, config);
  const mapping = createEventMappingState();
  let lastMessage = "";
  let started = false;
  let resolveIdle!: (text: string) => void;
  let rejectTurn!: (error: Error) => void;
  const completion = new Promise<string>((resolve, reject) => {
    resolveIdle = resolve;
    rejectTurn = reject;
  });
  completion.catch(() => undefined);
  let unsubscribe: (() => void) | undefined;
  try {
    unsubscribe = session.on(
      bindCopilotContext((event: SessionEvent) => {
        try {
          const mapped = mapSessionEvent(event, mapping);
          if (mapped) emit(mapped);
          const topLevel = !(event as { agentId?: string }).agentId;
          switch (event.type) {
            case "user.message":
              if (topLevel) started = true;
              break;
            case "assistant.message":
              if (topLevel && !event.data.parentToolCallId) {
                started = true;
                if (typeof event.data.content === "string" && event.data.content.trim() !== "") {
                  lastMessage = event.data.content;
                }
              }
              break;
            case "session.idle":
              if (!topLevel || !started) break;
              if (event.data.aborted) rejectTurn(new CopilotAbortedError("The runtime aborted the turn"));
              else resolveIdle(lastMessage);
              break;
            case "session.error":
              rejectTurn(mapped?.type === "error" ? mapped.error : new CopilotSessionError(event.data.message, event.data.errorType));
              break;
          }
        } catch (error) {
          runtime.logger.warn("Copilot event handling failed", { error: messageOf(error) });
        }
      }),
    );
    emit({ type: "turn-start", sessionId });

    let text: string;
    let value: unknown;
    if (req.schema) {
      value = await runStructured(session, req.schema, req.prompt, turn, completion);
      text = lastMessage;
    } else {
      await raceAbort(session.send({ prompt: req.prompt }), signal);
      // Some runtimes don't echo `user.message`; once send() has returned, the next idle is ours.
      started = true;
      text = await raceAbort(completion, signal);
    }
    emit({ type: "done", text, sessionId });
    return { text, value, sessionId };
  } catch (error) {
    // A dead runtime marks the client failed and aborts this turn with CopilotRuntimeUnavailableError.
    runtime.checkConnection(client);
    if (signal.aborted) {
      await session.abort().catch((e) => runtime.logger.warn("Failed to abort Copilot session", { sessionId, error: messageOf(e) }));
    }
    throw error;
  } finally {
    unsubscribe?.();
    await session.disconnect().catch((e) =>
      runtime.logger.warn("Failed to disconnect Copilot session", { sessionId, error: messageOf(e) }),
    );
    if (turn.ephemeral) {
      await client.deleteSession(sessionId).catch((e) =>
        runtime.logger.warn("Failed to delete ephemeral Copilot session", { sessionId, error: messageOf(e) }),
      );
    }
  }
}

async function runStructured(
  session: SessionLike,
  schema: StructuredSchema<unknown>,
  prompt: string,
  turn: TurnRuntime,
  failure: Promise<unknown>,
): Promise<unknown> {
  const { jsonSchema, wrapped } = responseJsonSchema(schema);
  // Validation happens here (not inside the SDK) so failures surface as CopilotOutputValidationError.
  const responseSchema = { _output: undefined as unknown, toJSONSchema: () => jsonSchema, parse: (v: unknown) => v };
  const remaining = Math.max(1, turn.deadline - Date.now());
  let raw: unknown;
  try {
    raw = await raceAbort(
      Promise.race([session.sendAndWait({ prompt }, responseSchema, remaining + 1_000), failure.then(() => new Promise<never>(() => undefined))]),
      turn.signal,
    );
  } catch (error) {
    if (error instanceof SyntaxError) {
      throw new CopilotOutputValidationError(`Structured result is not valid JSON: ${error.message}`, { cause: error });
    }
    if (error instanceof Error && /without a structured assistant response/i.test(error.message)) {
      throw new CopilotOutputValidationError(error.message, { cause: error });
    }
    throw error;
  }
  if (wrapped) {
    if (!raw || typeof raw !== "object" || !("value" in raw)) {
      throw new CopilotOutputValidationError('Structured result is missing the "value" wrapper property');
    }
    raw = (raw as { value: unknown }).value;
  }
  try {
    return await schema.parse(raw);
  } catch (error) {
    throw new CopilotOutputValidationError(`Structured result failed schema validation: ${messageOf(error)}`, {
      cause: error,
    });
  }
}

/**
 * JSON Schema for a structured result. Providers require an object at the root of a strict JSON
 * schema response format, so non-object schemas (enums, arrays, primitives) are wrapped as
 * `{ value: <schema> }` and unwrapped before validation.
 */
export function responseJsonSchema(schema: StructuredSchema<unknown>): { jsonSchema: Record<string, unknown>; wrapped: boolean } {
  let json: Record<string, unknown>;
  if (typeof schema.toJSONSchema === "function") {
    const { $schema: _ignored, ...rest } = schema.toJSONSchema() as Record<string, unknown>;
    json = rest;
  } else {
    json = toJsonSchema(schema);
  }
  if (json.type === "object") return { jsonSchema: json, wrapped: false };
  return {
    jsonSchema: { type: "object", properties: { value: json }, required: ["value"], additionalProperties: false },
    wrapped: true,
  };
}

/** Resolves context-derived MCP `headers`/`env` for the session being created or resumed. */
export async function resolveMcpServers(
  servers: Record<string, CopilotMCPServerConfig>,
  context: CopilotContext,
): Promise<Record<string, MCPServerConfig>> {
  const result: Record<string, MCPServerConfig> = {};
  for (const [name, server] of Object.entries(servers)) {
    const resolved: Record<string, unknown> = { ...server };
    for (const key of ["headers", "env"] as const) {
      const value = (server as Record<string, unknown>)[key] as ContextualStringMap | undefined;
      if (value === undefined) continue;
      try {
        resolved[key] = await resolveStringMap(value, context);
      } catch (error) {
        throw new CopilotConfigurationError(`MCP server "${name}": resolving ${key} failed: ${messageOf(error)}`, { cause: error });
      }
    }
    result[name] = resolved as unknown as MCPServerConfig;
  }
  return result;
}

async function resolveStringMap(value: ContextualStringMap, context: CopilotContext): Promise<Record<string, string>> {
  const entries = typeof value === "function" ? Object.entries((await value(context)) ?? {}) : Object.entries(value);
  const result: Record<string, string> = {};
  for (const [key, entry] of entries) {
    const resolved = typeof entry === "function" ? await entry(context) : entry;
    if (resolved !== undefined && resolved !== null) result[key] = String(resolved);
  }
  return result;
}

async function openSession(
  client: ClientLike,
  req: TurnRequest,
  turn: TurnRuntime,
  config: SessionConfig,
): Promise<SessionLike> {
  const { sessionId, signal } = turn;
  const lateSession = (s: SessionLike) => void s.disconnect().catch(() => undefined);
  const create = () => raceAbort(client.createSession(config), signal, lateSession);
  const resume = () => {
    const { sessionId: _id, name: _name, trustWorkingDirectory: _t, refreshCustomInstructions: _r, cloud: _c, ...rest } =
      config;
    const resumeConfig: ResumeSessionConfig = rest;
    return raceAbort(client.resumeSession(sessionId, resumeConfig), signal, lateSession);
  };
  if (turn.ephemeral) return create();

  const conversationId = req.conversationId!;
  const mode = req.mode;
  if (client.getSessionMetadata) {
    const exists = (await raceAbort(client.getSessionMetadata(sessionId), signal)) != null;
    if (exists) {
      if (mode === "create-only") throw new CopilotConversationExistsError(conversationId);
      return resume();
    }
    if (mode === "resume-only") throw new CopilotConversationNotFoundError(conversationId);
    return create();
  }
  // No metadata lookup: probe with resume and treat "not found" errors as absence.
  if (mode === "create-only") {
    try {
      return await create();
    } catch (error) {
      if (isAlreadyExistsError(error)) throw new CopilotConversationExistsError(conversationId);
      throw error;
    }
  }
  try {
    return await resume();
  } catch (error) {
    if (!isNotFoundError(error) || signal.aborted) throw error;
    if (mode === "resume-only") throw new CopilotConversationNotFoundError(conversationId);
    return create();
  }
}

export function isNotFoundError(error: unknown): boolean {
  return error instanceof Error && /not\s*found|does not exist|no such session|unknown session/i.test(error.message);
}

function isAlreadyExistsError(error: unknown): boolean {
  return error instanceof Error && /already exists/i.test(error.message);
}

function toSystemMessage(instructions: Instructions | undefined): SystemMessageConfig | undefined {
  if (instructions === undefined) return undefined;
  if (typeof instructions === "string") return { mode: "append", content: instructions };
  return instructions as SystemMessageConfig;
}

/** Binds every hook to the current turn context (the SDK invokes hooks from its JSON-RPC handlers). */
function bindHooks(hooks: SessionHooks | undefined): SessionHooks | undefined {
  if (!hooks) return undefined;
  const bound: Record<string, unknown> = {};
  for (const [name, fn] of Object.entries(hooks)) {
    bound[name] = typeof fn === "function" ? bindCopilotContext(fn as (...args: unknown[]) => unknown) : fn;
  }
  return bound as SessionHooks;
}

function deliverToListener(runtime: CopilotRuntime, event: CopilotAgentEvent, context: CopilotContext): void {
  const listener = runtime.options.onEvent;
  if (!listener) return;
  const report = (error: unknown) =>
    runtime.logger.warn("Copilot event listener failed", { event: event.type, error: messageOf(error) });
  try {
    const result: unknown = runWithCopilotContext(context, () => listener(event, context));
    if (result && typeof (result as Promise<unknown>).then === "function") {
      (result as Promise<unknown>).then(undefined, report);
    }
  } catch (error) {
    report(error);
  }
}

// ---------------------------------------------------------------------------
// Async helpers
// ---------------------------------------------------------------------------

/**
 * Races `promise` against `signal`. If the signal wins and the promise later resolves, `onLate`
 * receives the value so resources (sessions, leases) aren't leaked.
 */
function raceAbort<T>(promise: Promise<T>, signal: AbortSignal, onLate?: (value: T) => void): Promise<T> {
  if (signal.aborted) {
    promise.then((v) => onLate?.(v), () => undefined);
    return Promise.reject(abortReason(signal));
  }
  return new Promise<T>((resolve, reject) => {
    let settled = false;
    const onAbort = () => {
      if (settled) return;
      settled = true;
      reject(abortReason(signal));
    };
    signal.addEventListener("abort", onAbort, { once: true });
    promise.then(
      (value) => {
        signal.removeEventListener("abort", onAbort);
        if (settled) {
          onLate?.(value);
          return;
        }
        settled = true;
        resolve(value);
      },
      (error) => {
        signal.removeEventListener("abort", onAbort);
        if (settled) return;
        settled = true;
        reject(error);
      },
    );
  });
}

/** Resolves to the response, or `undefined` when the approval window elapses. Rejects on abort. */
function withWindow<T>(promise: Promise<T>, windowMs: number, signal: AbortSignal): Promise<T | undefined> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<undefined>((resolve) => {
    timer = setTimeout(() => resolve(undefined), windowMs);
  });
  return raceAbort(Promise.race([promise, timeout]), signal).finally(() => clearTimeout(timer));
}

/**
 * Adapts a turn to a cold `AsyncIterable`: each iteration runs a new turn. Events are buffered
 * until consumed. Leaving the loop early (`break`, `return`, a thrown error) aborts the turn and
 * waits for its cleanup. Failures are delivered as a final `{ type: "error" }` event and the
 * iteration then ends normally (it does not throw).
 */
function streamTurn(
  run: (sink: (event: CopilotAgentEvent) => void, signal: AbortSignal) => Promise<TurnResult>,
  callerSignal: AbortSignal | undefined,
): AsyncIterable<CopilotAgentEvent> {
  return {
    [Symbol.asyncIterator]() {
      return (async function* () {
        const queue: CopilotAgentEvent[] = [];
        let wake: (() => void) | undefined;
        let finished = false;
        const consumer = new AbortController();
        const signal = callerSignal ? AbortSignal.any([callerSignal, consumer.signal]) : consumer.signal;
        const errorsSeen = new WeakSet<Error>();
        const push = (event: CopilotAgentEvent) => {
          if (event.type === "error") errorsSeen.add(event.error);
          queue.push(event);
          wake?.();
        };
        const settled = run(push, signal)
          .then(
            () => undefined,
            (error: unknown) => {
              // Failures raised before the executor could emit (e.g. invalid arguments) still reach the consumer.
              const err = error instanceof Error ? error : new Error(String(error));
              if (!errorsSeen.has(err)) push({ type: "error", error: err });
            },
          )
          .finally(() => {
            finished = true;
            wake?.();
          });
        let completed = false;
        try {
          for (;;) {
            while (queue.length > 0) {
              yield queue.shift()!;
            }
            if (finished) {
              completed = true;
              return;
            }
            await new Promise<void>((resolve) => (wake = resolve));
            wake = undefined;
          }
        } finally {
          if (!completed) {
            consumer.abort(new CopilotAbortedError("The stream consumer stopped before the turn completed"));
          }
          await settled;
        }
      })();
    },
  };
}

function messageOf(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}
