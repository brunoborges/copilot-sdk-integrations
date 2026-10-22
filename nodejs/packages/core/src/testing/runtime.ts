import { AsyncLocalStorage } from "node:async_hooks";
import { randomUUID } from "node:crypto";
import type {
  AttributedPermissionResult,
  CopilotClientOptions,
  JsonValue,
  PermissionRequest,
  PermissionRequestResult,
  PermissionResult,
  ResumeSessionConfig,
  SessionConfig,
  SessionEvent,
  SessionHooks,
  Tool,
  ToolInvocation,
  ToolSet,
  ToolResultObject,
} from "@github/copilot-sdk";
import type { ClientFactory, ClientLike, SessionLike, StructuredSchema } from "../types.js";
import {
  PromptScript,
  matches,
  type PromptMatcher,
  type ScriptRule,
  type ScriptStep,
  type SessionEventInit,
  type TestToolCall,
  type TestTurn,
} from "./script.js";

type EventOf<T extends SessionEvent["type"]> = Extract<SessionEvent, { type: T }>;
type AnySessionConfig = SessionConfig | ResumeSessionConfig;

/**
 * Turns run in the async context captured when this module loaded, like the real SDK,
 * whose events and callbacks arrive from the JSON-RPC connection. Consumers that need
 * request context in tools, hooks, or listeners must bind it themselves.
 */
const runDetached = AsyncLocalStorage.snapshot();

/** Thrown internally to unwind a turn on `abort()`. */
class TurnAborted extends Error {}

/** A permission request seen by the runtime and the decision returned for it. */
export interface TestPermissionRequest {
  sessionId: string;
  request: PermissionRequest;
  result: PermissionRequestResult;
}

/** A prompt received by the runtime. */
export interface TestPrompt {
  sessionId: string;
  messageId: string;
  prompt: string;
}

export interface TestModel {
  id: string;
  name?: string;
  [key: string]: unknown;
}

export interface TestSessionMetadata {
  sessionId: string;
  startTime: Date;
  modifiedTime: Date;
  summary?: string;
  isRemote: boolean;
}

export interface CopilotTestRuntimeOptions {
  /** Models returned by `listModels()`. */
  models?: TestModel[];
  /** Model reported in events when the session config has none. Default `"test-model"`. */
  defaultModel?: string;
}

const DEFAULT_MODELS: TestModel[] = [{ id: "test-model", name: "Test Model" }];

interface SessionState {
  id: string;
  config: AnySessionConfig;
  events: SessionEvent[];
  lastEventId: string | null;
  handles: Set<TestSession>;
  queue: Promise<void>;
  current?: { controller: AbortController; done: Promise<void> };
  startTime: Date;
  modifiedTime: Date;
  summary?: string;
}

/**
 * Scripted, in-memory implementation of the client/session contract (`ClientLike` /
 * `SessionLike`). It emits real SDK `SessionEvent` shapes, calls registered tools
 * through the session's permission handler and hooks, and records every interaction.
 *
 * ```ts
 * const runtime = new CopilotTestRuntime();
 * runtime.onPrompt(/order 42/).callTool("lookupOrder", { id: "42" }).thenReply("Order 42 shipped.")
 *        .onPrompt(/.*\/).reply("Hello");
 * const copilot = createCopilot({ clientFactory: runtime.clientFactory });
 * ```
 */
export class CopilotTestRuntime {
  /** Use as `CopilotOptions.clientFactory`. Every created client shares this runtime's state. */
  readonly clientFactory: ClientFactory = (options) => {
    this._clientOptions.push(options);
    return new TestClient(this);
  };

  private rules: ScriptRule[] = [];
  private sessions = new Map<string, SessionState>();
  private models: TestModel[];
  private readonly defaultModel: string;
  private startFailure?: { error: Error; remaining: number };
  private toolCallSeq = 0;

  private _prompts: TestPrompt[] = [];
  private _createdSessions: SessionConfig[] = [];
  private _resumedSessions: { sessionId: string; config: ResumeSessionConfig }[] = [];
  private _deletedSessions: string[] = [];
  private _toolCalls: TestToolCall[] = [];
  private _permissionRequests: TestPermissionRequest[] = [];
  private _clientOptions: CopilotClientOptions[] = [];
  private _abortCount = 0;
  private _crashCount = 0;
  private _epoch = 0;
  private _started = false;
  private _stopped = false;

  constructor(options: CopilotTestRuntimeOptions = {}) {
    this.models = options.models ? [...options.models] : [...DEFAULT_MODELS];
    this.defaultModel = options.defaultModel ?? "test-model";
  }

  // -------------------------------------------------------------------------
  // Scripting
  // -------------------------------------------------------------------------

  /** Register a rule. Rules match in registration order; the first match with uses left wins. */
  onPrompt(matcher: PromptMatcher): PromptScript {
    const rule: ScriptRule = { matcher, steps: [], remaining: Infinity };
    this.rules.push(rule);
    return new PromptScript(rule, (next) => this.onPrompt(next));
  }

  /** Make `start()` (and lazy auto-start) reject with `error`, `times` times (default: until cleared). Pass `undefined` to clear. */
  failStart(error: Error | string | undefined, times = Infinity): this {
    this.startFailure =
      error === undefined ? undefined : { error: typeof error === "string" ? new Error(error) : error, remaining: times };
    return this;
  }

  /**
   * Simulate the runtime process dying: in-flight turns stop without further events, session
   * handles are disconnected, and clients report `isConnected() === false` until the next start.
   * Sessions are kept, as the runtime keeps them on disk.
   */
  crash(): this {
    for (const state of this.sessions.values()) {
      state.current?.controller.abort();
      for (const handle of state.handles) handle.markDisconnected();
      state.handles.clear();
    }
    this._started = false;
    this._epoch++;
    this._crashCount++;
    return this;
  }

  /** Replace the models returned by `listModels()`. */
  setModels(models: TestModel[]): this {
    this.models = [...models];
    return this;
  }

  /** Clear rules, sessions, recordings, start failures, and models. In-flight turns are aborted. */
  reset(): void {
    for (const state of this.sessions.values()) state.current?.controller.abort();
    this.rules = [];
    this.sessions.clear();
    this.models = [...DEFAULT_MODELS];
    this.startFailure = undefined;
    this.toolCallSeq = 0;
    this._prompts = [];
    this._createdSessions = [];
    this._resumedSessions = [];
    this._deletedSessions = [];
    this._toolCalls = [];
    this._permissionRequests = [];
    this._clientOptions = [];
    this._abortCount = 0;
    this._crashCount = 0;
    this._started = false;
    this._stopped = false;
  }

  // -------------------------------------------------------------------------
  // Recordings
  // -------------------------------------------------------------------------

  /** Prompt text in arrival order. */
  get prompts(): string[] {
    return this._prompts.map((p) => p.prompt);
  }
  /** Prompts with their session and message ids. */
  get receivedPrompts(): readonly TestPrompt[] {
    return this._prompts;
  }
  get createdSessions(): readonly SessionConfig[] {
    return this._createdSessions;
  }
  get resumedSessions(): readonly { sessionId: string; config: ResumeSessionConfig }[] {
    return this._resumedSessions;
  }
  get deletedSessions(): readonly string[] {
    return this._deletedSessions;
  }
  get toolCalls(): readonly TestToolCall[] {
    return this._toolCalls;
  }
  get permissionRequests(): readonly TestPermissionRequest[] {
    return this._permissionRequests;
  }
  get clientOptions(): readonly CopilotClientOptions[] {
    return this._clientOptions;
  }
  /** Number of `abort()` calls on any session. */
  get abortCount(): number {
    return this._abortCount;
  }
  get started(): boolean {
    return this._started;
  }
  /** Number of `crash()` calls. */
  get crashCount(): number {
    return this._crashCount;
  }
  get stopped(): boolean {
    return this._stopped;
  }
  /** Ids of the sessions currently held in memory. */
  get sessionIds(): string[] {
    return [...this.sessions.keys()];
  }
  /** Latest config (create or resume) of a session, or `undefined`. */
  sessionConfig(sessionId: string): AnySessionConfig | undefined {
    return this.sessions.get(sessionId)?.config;
  }
  /** All events emitted on a session so far. */
  events(sessionId: string): SessionEvent[] {
    return [...(this.sessions.get(sessionId)?.events ?? [])];
  }

  // -------------------------------------------------------------------------
  // Client operations (used by TestClient)
  // -------------------------------------------------------------------------

  /** @internal */
  async start(): Promise<void> {
    if (this.startFailure && this.startFailure.remaining > 0) {
      this.startFailure.remaining--;
      const { error } = this.startFailure;
      if (this.startFailure.remaining <= 0) this.startFailure = undefined;
      throw error;
    }
    this._started = true;
    this._stopped = false;
    this._epoch++;
  }

  /** @internal Incremented on every start and crash, so clients of a dead runtime know they are stale. */
  get epoch(): number {
    return this._epoch;
  }

  /** @internal */
  async stop(): Promise<Error[]> {
    const pending: Promise<void>[] = [];
    for (const state of this.sessions.values()) {
      if (state.current) {
        state.current.controller.abort();
        pending.push(state.current.done);
      }
    }
    await Promise.all(pending);
    for (const state of this.sessions.values()) {
      for (const handle of state.handles) handle.markDisconnected();
      state.handles.clear();
    }
    this._started = false;
    this._stopped = true;
    return [];
  }

  /** @internal */
  async createSession(config: SessionConfig): Promise<TestSession> {
    await this.ensureStarted();
    this._createdSessions.push(config);
    const id = config.sessionId ?? randomUUID();
    const previous = this.sessions.get(id);
    previous?.current?.controller.abort();
    const now = new Date();
    const state: SessionState = {
      id,
      config,
      events: [],
      lastEventId: null,
      handles: new Set(),
      queue: Promise.resolve(),
      startTime: now,
      modifiedTime: now,
    };
    this.sessions.set(id, state);
    return this.attach(state);
  }

  /** @internal */
  async resumeSession(sessionId: string, config: ResumeSessionConfig): Promise<TestSession> {
    await this.ensureStarted();
    const state = this.sessions.get(sessionId);
    if (!state) throw new Error(`Session not found: ${sessionId}`);
    this._resumedSessions.push({ sessionId, config });
    state.config = config;
    state.modifiedTime = new Date();
    return this.attach(state);
  }

  /** @internal */
  async deleteSession(sessionId: string): Promise<void> {
    this._deletedSessions.push(sessionId);
    const state = this.sessions.get(sessionId);
    if (!state) return;
    this.sessions.delete(sessionId);
    state.current?.controller.abort();
    for (const handle of state.handles) handle.markDisconnected();
    state.handles.clear();
  }

  /** @internal */
  async getSessionMetadata(sessionId: string): Promise<TestSessionMetadata | undefined> {
    const state = this.sessions.get(sessionId);
    if (!state) return undefined;
    return {
      sessionId,
      startTime: state.startTime,
      modifiedTime: state.modifiedTime,
      ...(state.summary === undefined ? {} : { summary: state.summary }),
      isRemote: false,
    };
  }

  /** @internal */
  async listModels(): Promise<TestModel[]> {
    return this.models.map((m) => ({ ...m }));
  }

  private async ensureStarted(): Promise<void> {
    if (!this._started) await this.start();
  }

  private attach(state: SessionState): TestSession {
    const handle = new TestSession(this, state);
    state.handles.add(handle);
    return handle;
  }

  // -------------------------------------------------------------------------
  // Session operations (used by TestSession)
  // -------------------------------------------------------------------------

  /** @internal */
  enqueueTurn(state: SessionState, prompt: string): string {
    const messageId = randomUUID();
    this._prompts.push({ sessionId: state.id, messageId, prompt });
    state.summary ??= prompt.slice(0, 80);
    state.queue = state.queue.then(
      () =>
        new Promise<void>((resolve) => {
          // Start after `send()` resolves, as events arrive after the RPC response.
          setImmediate(() => {
            if (this.sessions.get(state.id) !== state) return resolve();
            const controller = new AbortController();
            const done = runDetached(() => this.runTurn(state, messageId, prompt, controller.signal)).finally(() => {
              if (state.current?.controller === controller) state.current = undefined;
            });
            state.current = { controller, done };
            done.then(resolve, resolve);
          });
        }),
    );
    return messageId;
  }

  /** @internal */
  async abortTurn(state: SessionState): Promise<void> {
    this._abortCount++;
    const current = state.current;
    if (!current) return;
    current.controller.abort();
    await current.done;
  }

  private emit<T extends SessionEvent["type"]>(
    state: SessionState,
    type: T,
    data: EventOf<T>["data"],
    ephemeral?: boolean,
  ): EventOf<T> {
    return this.dispatch(state, { type, data, ...(ephemeral ? { ephemeral } : {}) } as SessionEventInit) as EventOf<T>;
  }

  private dispatch(state: SessionState, init: SessionEventInit): SessionEvent {
    const event = {
      ...init,
      id: randomUUID(),
      timestamp: new Date().toISOString(),
      parentId: state.lastEventId,
    } as SessionEvent;
    state.lastEventId = event.id;
    state.events.push(event);
    state.modifiedTime = new Date();
    for (const handle of [...state.handles]) handle.deliver(event);
    return event;
  }

  private async runTurn(state: SessionState, messageId: string, originalPrompt: string, signal: AbortSignal): Promise<void> {
    const config = state.config;
    const model = config.model ?? this.defaultModel;
    const turnToolCalls: TestToolCall[] = [];
    let turnIndex = 0;
    let turnOpen = false;
    let prompt = originalPrompt;

    const turn: TestTurn = {
      sessionId: state.id,
      get prompt() {
        return prompt;
      },
      toolCalls: turnToolCalls,
      toolResult: (name) => [...turnToolCalls].reverse().find((c) => c.name === name)?.result,
    };
    const checkAbort = () => {
      if (signal.aborted) throw new TurnAborted();
    };
    const yieldTick = async () => {
      await new Promise<void>((resolve) => setImmediate(resolve));
      checkAbort();
    };
    const openTurn = () => {
      if (turnOpen) return;
      turnOpen = true;
      this.emit(state, "assistant.turn_start", { turnId: String(turnIndex), model });
    };
    const closeTurn = () => {
      if (!turnOpen) return;
      turnOpen = false;
      this.emit(state, "assistant.turn_end", { turnId: String(turnIndex), model });
      turnIndex++;
    };
    const assistantMessage = (content: string, extra: Partial<EventOf<"assistant.message">["data"]> = {}) =>
      this.emit(state, "assistant.message", {
        messageId: randomUUID(),
        content,
        originatingMessageId: messageId,
        turnId: String(turnIndex),
        model,
        ...extra,
      });

    try {
      const submitted = await raceAbort(
        Promise.resolve(
          config.hooks?.onUserPromptSubmitted?.(
            { ...this.hookBase(state), prompt },
            { sessionId: state.id },
          ),
        ),
        signal,
      );
      if (submitted && typeof submitted.modifiedPrompt === "string") prompt = submitted.modifiedPrompt;

      this.emit(state, "user.message", {
        content: originalPrompt,
        messageId,
        ...(prompt !== originalPrompt ? { transformedContent: prompt } : {}),
      });

      const rule = this.rules.find((r) => r.remaining > 0 && matches(r.matcher, prompt));
      if (!rule) {
        await yieldTick();
        this.emit(state, "session.error", {
          errorType: "test_runtime",
          message:
            `CopilotTestRuntime: no scripted reply matches prompt ${JSON.stringify(prompt)}` +
            ` (session ${state.id}). Register one with runtime.onPrompt(...).`,
          originatingMessageId: messageId,
        });
        this.emit(state, "session.idle", {}, true);
        return;
      }
      rule.remaining--;

      const steps = rule.steps;
      for (let i = 0; i < steps.length; i++) {
        await yieldTick();
        const step = steps[i];
        switch (step.kind) {
          case "delay":
            await sleep(step.ms, signal);
            break;
          case "reasoning": {
            openTurn();
            const reasoningId = randomUUID();
            this.emit(state, "assistant.reasoning_delta", { reasoningId, deltaContent: step.text }, true);
            this.emit(state, "assistant.reasoning", { reasoningId, content: step.text });
            break;
          }
          case "reply": {
            openTurn();
            const text = typeof step.text === "function" ? step.text(turn) : step.text;
            if (config.streaming) {
              const id = randomUUID();
              this.emit(state, "assistant.message_delta", { messageId: id, deltaContent: text }, true);
              assistantMessage(text, { messageId: id });
            } else {
              assistantMessage(text);
            }
            break;
          }
          case "stream": {
            openTurn();
            const id = randomUUID();
            for (const chunk of step.chunks) {
              this.emit(state, "assistant.message_delta", { messageId: id, deltaContent: chunk }, true);
              await yieldTick();
            }
            assistantMessage(step.chunks.join(""), { messageId: id });
            break;
          }
          case "usage":
            this.emit(
              state,
              "assistant.usage",
              {
                model: step.usage.model ?? model,
                ...(step.usage.inputTokens === undefined ? {} : { inputTokens: step.usage.inputTokens }),
                ...(step.usage.outputTokens === undefined ? {} : { outputTokens: step.usage.outputTokens }),
                ...(step.usage.cost === undefined ? {} : { cost: step.usage.cost }),
              },
              true,
            );
            break;
          case "emit":
            this.dispatch(state, step.event(turn));
            break;
          case "fail":
            closeTurn();
            this.emit(state, "session.error", {
              errorType: step.errorType,
              message: step.message,
              originatingMessageId: messageId,
            });
            this.emit(state, "session.idle", {}, true);
            return;
          case "tool": {
            const group: Extract<ScriptStep, { kind: "tool" }>[] = [step];
            while (steps[i + 1]?.kind === "tool") group.push(steps[++i] as Extract<ScriptStep, { kind: "tool" }>);
            openTurn();
            const calls = group.map((s) => ({ ...s, toolCallId: `call_${++this.toolCallSeq}` }));
            assistantMessage("", {
              toolRequests: calls.map((c) => ({
                toolCallId: c.toolCallId,
                name: c.name,
                arguments: c.args as JsonValue,
                type: "function" as const,
              })),
            });
            for (const call of calls) {
              await yieldTick();
              const record = await this.executeTool(state, call.toolCallId, call.name, call.args, String(turnIndex), signal);
              turnToolCalls.push(record);
            }
            closeTurn();
            break;
          }
        }
      }
      await yieldTick();
      closeTurn();
      this.emit(state, "session.idle", {}, true);
    } catch (error) {
      if (!(error instanceof TurnAborted) && !signal.aborted) {
        closeTurn();
        this.emit(state, "session.error", {
          errorType: "test_runtime",
          message: error instanceof Error ? error.message : String(error),
          ...(error instanceof Error && error.stack ? { stack: error.stack } : {}),
          originatingMessageId: messageId,
        });
        this.emit(state, "session.idle", {}, true);
        return;
      }
      if (this.sessions.get(state.id) !== state) return;
      this.emit(state, "abort", { reason: "user_initiated" });
      this.emit(state, "session.idle", { aborted: true }, true);
    }
  }

  private hookBase(state: SessionState) {
    return { sessionId: state.id, timestamp: new Date(), workingDirectory: state.config.workingDirectory ?? process.cwd() };
  }

  private async executeTool(
    state: SessionState,
    toolCallId: string,
    name: string,
    initialArgs: unknown,
    turnId: string,
    signal: AbortSignal,
  ): Promise<TestToolCall> {
    const config = state.config;
    const hooks: SessionHooks | undefined = config.hooks;
    const record: TestToolCall = { sessionId: state.id, toolCallId, name, args: initialArgs, approved: false };
    this._toolCalls.push(record);
    let args = initialArgs;

    this.emit(state, "tool.execution_start", { toolCallId, toolName: name, arguments: args as JsonValue, turnId });

    const fail = async (message: string, code?: string) => {
      if (hooks?.onPostToolUseFailure) {
        try {
          await raceAbort(
            Promise.resolve(hooks.onPostToolUseFailure({ ...this.hookBase(state), toolName: name, toolArgs: args, error: message }, { sessionId: state.id })),
            signal,
          );
        } catch (error) {
          if (error instanceof TurnAborted) throw error;
        }
      }
      record.error = message;
      this.emit(state, "tool.execution_complete", {
        toolCallId,
        success: false,
        error: { message, ...(code ? { code } : {}) },
        turnId,
      });
      return record;
    };

    try {
      const tool = (config.tools ?? []).find((t: Tool<any>) => t.name === name);
      if (!tool) {
        const registered = (config.tools ?? []).map((t: Tool<any>) => t.name);
        return await fail(
          `Tool "${name}" is not registered on session ${state.id}. Registered tools: ${registered.length ? registered.join(", ") : "(none)"}`,
          "tool_not_found",
        );
      }

      if (!isCustomToolEnabled(name, config)) {
        return await fail(
          `Tool "${name}" is not available on session ${state.id}: it is filtered out by availableTools/excludedTools`,
          "tool_not_available",
        );
      }

      let hookAllowed = false;
      if (hooks?.onPreToolUse) {
        const pre = await raceAbort(
          Promise.resolve(hooks.onPreToolUse({ ...this.hookBase(state), toolName: name, toolArgs: args }, { sessionId: state.id })),
          signal,
        );
        if (pre?.permissionDecision === "deny") {
          return await fail(
            `Tool "${name}" was denied by the preToolUse hook${pre.permissionDecisionReason ? `: ${pre.permissionDecisionReason}` : ""}`,
            "denied",
          );
        }
        if (pre && "modifiedArgs" in pre && pre.modifiedArgs !== undefined) {
          args = pre.modifiedArgs;
          record.args = args;
        }
        hookAllowed = pre?.permissionDecision === "allow";
      }

      if (!tool.skipPermission && !hookAllowed) {
        const approved = await this.requestPermission(state, {
          kind: "custom-tool",
          toolCallId,
          toolName: name,
          toolDescription: tool.description ?? "",
          args: args as JsonValue,
        }, toolCallId, signal);
        if (!approved) return await fail(`Permission denied for tool "${name}"`, "denied");
      }
      record.approved = true;

      if (!tool.handler) return await fail(`Tool "${name}" has no handler`, "no_handler");

      const toolController = new AbortController();
      const onAbort = () => toolController.abort();
      signal.addEventListener("abort", onAbort, { once: true });
      let raw: unknown;
      try {
        const invocation: ToolInvocation = {
          sessionId: state.id,
          toolCallId,
          toolName: name,
          arguments: args,
          signal: toolController.signal,
        };
        raw = await raceAbort(Promise.resolve().then(() => tool.handler!(args, invocation)), signal);
      } catch (error) {
        if (error instanceof TurnAborted) {
          record.error = "Tool execution aborted";
          this.emit(state, "tool.execution_complete", {
            toolCallId,
            success: false,
            error: { message: "Tool execution aborted", code: "aborted" },
            turnId,
          });
          throw error;
        }
        return await fail(error instanceof Error ? error.message : String(error));
      } finally {
        signal.removeEventListener("abort", onAbort);
        toolController.abort();
      }
      record.result = raw;

      let result = normalizeToolResult(raw);
      if (hooks?.onPostToolUse) {
        const post = await raceAbort(
          Promise.resolve(hooks.onPostToolUse({ ...this.hookBase(state), toolName: name, toolArgs: args, toolResult: result }, { sessionId: state.id })),
          signal,
        );
        if (post?.modifiedResult) result = post.modifiedResult;
      }
      const success = result.resultType === "success";
      if (!success) record.error = result.error ?? result.textResultForLlm;
      this.emit(state, "tool.execution_complete", {
        toolCallId,
        success,
        result: { content: result.textResultForLlm },
        ...(success ? {} : { error: { message: result.error ?? result.textResultForLlm, code: result.resultType } }),
        turnId,
      });
      return record;
    } catch (error) {
      if (error instanceof TurnAborted) {
        if (record.error === undefined) {
          record.error = "Tool execution aborted";
          this.emit(state, "tool.execution_complete", {
            toolCallId,
            success: false,
            error: { message: "Tool execution aborted", code: "aborted" },
            turnId,
          });
        }
        throw error;
      }
      if (record.error !== undefined) throw error;
      return await fail(error instanceof Error ? error.message : String(error));
    }
  }

  private async requestPermission(
    state: SessionState,
    request: PermissionRequest,
    toolCallId: string,
    signal: AbortSignal,
  ): Promise<boolean> {
    const requestId = randomUUID();
    this.emit(state, "permission.requested", { requestId, permissionRequest: request });
    const handler = state.config.onPermissionRequest;
    let result: PermissionRequestResult;
    if (!handler) {
      result = { kind: "user-not-available" };
    } else {
      try {
        const raw: PermissionRequestResult | AttributedPermissionResult = await raceAbort(
          Promise.resolve().then(() => handler(request, { sessionId: state.id })),
          signal,
        );
        result = raw.kind === "attributed" ? raw.result : raw;
      } catch (error) {
        if (error instanceof TurnAborted) throw error;
        // The SDK answers `user-not-available` when the handler throws.
        result = { kind: "user-not-available" };
      }
    }
    this._permissionRequests.push({ sessionId: state.id, request, result });
    const approved = result.kind.startsWith("approve");
    this.emit(state, "permission.completed", { requestId, toolCallId, result: toPermissionResult(result) });
    return approved;
  }
}

function toPermissionResult(result: PermissionRequestResult): PermissionResult {
  switch (result.kind) {
    case "approve-once":
    case "approve-for-session":
    case "approve-read-only-for-session":
    case "approve-for-location":
    case "approve-permanently":
    case "approved":
      return { kind: "approved" };
    case "reject":
      return { kind: "denied-interactively-by-user", ...(result.feedback ? { feedback: result.feedback } : {}) };
    case "denied-interactively-by-user":
    case "cancelled":
    case "denied-no-approval-rule-and-could-not-request-from-user":
      return result as PermissionResult;
    case "user-not-available":
    case "no-result":
      return { kind: "denied-no-approval-rule-and-could-not-request-from-user" };
    default:
      return result as PermissionResult;
  }
}

/**
 * Mirrors the runtime's ToolSet filtering for custom tools: a tool is enabled when `availableTools`
 * is unset or matches it (`custom:*`, `custom:<name>`, `<name>`), and `excludedTools` does not.
 */
function isCustomToolEnabled(name: string, config: AnySessionConfig): boolean {
  const matchesEntry = (entry: string) => entry === "custom:*" || entry === `custom:${name}` || entry === name;
  const list = (value: string[] | ToolSet | undefined) =>
    value === undefined ? undefined : Array.isArray(value) ? value : value.toArray();
  const available = list(config.availableTools);
  if (available !== undefined && !available.some(matchesEntry)) return false;
  return !(list(config.excludedTools) ?? []).some(matchesEntry);
}

function isToolResultObject(value: unknown): value is ToolResultObject {
  return (
    typeof value === "object" &&
    value !== null &&
    typeof (value as ToolResultObject).textResultForLlm === "string" &&
    typeof (value as ToolResultObject).resultType === "string"
  );
}

/** Mirrors the SDK: null/undefined -> "", strings as-is, ToolResultObject as-is, anything else JSON. */
function normalizeToolResult(raw: unknown): ToolResultObject {
  if (isToolResultObject(raw)) return raw;
  const text = raw == null ? "" : typeof raw === "string" ? raw : JSON.stringify(raw);
  return { textResultForLlm: text, resultType: "success" };
}

function raceAbort<T>(promise: Promise<T>, signal: AbortSignal): Promise<T> {
  if (signal.aborted) {
    promise.catch(() => {});
    return Promise.reject(new TurnAborted());
  }
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => reject(new TurnAborted());
    signal.addEventListener("abort", onAbort, { once: true });
    promise.then(
      (value) => {
        signal.removeEventListener("abort", onAbort);
        resolve(value);
      },
      (error) => {
        signal.removeEventListener("abort", onAbort);
        reject(error);
      },
    );
  });
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return raceAbort(
    new Promise<void>((resolve) => {
      const timer = setTimeout(resolve, ms);
      signal.addEventListener("abort", () => clearTimeout(timer), { once: true });
    }),
    signal,
  );
}

class TestClient implements ClientLike {
  private epoch?: number;
  constructor(private readonly runtime: CopilotTestRuntime) {}
  async start(): Promise<void> {
    await this.runtime.start();
    this.epoch = this.runtime.epoch;
  }
  async stop(): Promise<Error[]> {
    // A client of a crashed runtime must not stop its replacement.
    if (this.epoch !== undefined && this.epoch !== this.runtime.epoch) return [];
    return this.runtime.stop();
  }
  isConnected(): boolean {
    return this.runtime.started && (this.epoch === undefined || this.epoch === this.runtime.epoch);
  }
  createSession(config: SessionConfig): Promise<SessionLike> {
    return this.runtime.createSession(config);
  }
  resumeSession(sessionId: string, config: ResumeSessionConfig): Promise<SessionLike> {
    return this.runtime.resumeSession(sessionId, config);
  }
  deleteSession(sessionId: string): Promise<void> {
    return this.runtime.deleteSession(sessionId);
  }
  getSessionMetadata(sessionId: string): Promise<TestSessionMetadata | undefined> {
    return this.runtime.getSessionMetadata(sessionId);
  }
  listModels(): Promise<TestModel[]> {
    return this.runtime.listModels();
  }
  async getStatus(): Promise<{ version: string; protocolVersion: number }> {
    return { version: "test", protocolVersion: 0 };
  }
}

/** A session handle returned by the test client. Mirrors `CopilotSession` behavior for `SessionLike`. */
export class TestSession implements SessionLike {
  private handlers = new Set<(event: SessionEvent) => void>();
  private disconnected = false;

  /** @internal */
  constructor(
    private readonly runtime: CopilotTestRuntime,
    private readonly state: SessionState,
  ) {}

  get sessionId(): string {
    return this.state.id;
  }

  /** The latest create/resume config of this session. */
  get config(): SessionConfig | ResumeSessionConfig {
    return this.state.config;
  }

  async send(options: { prompt: string; mode?: "enqueue" | "immediate" }): Promise<string> {
    if (this.disconnected) throw new Error("Session is disconnected");
    return this.runtime.enqueueTurn(this.state, options.prompt);
  }

  async sendAndWait(
    options: { prompt: string },
    responseSchema?: StructuredSchema<unknown> | number,
    timeout?: number,
  ): Promise<unknown> {
    const schema = typeof responseSchema === "object" && responseSchema !== null ? responseSchema : undefined;
    const effectiveTimeout = (typeof responseSchema === "number" ? responseSchema : timeout) ?? 60_000;
    if (this.disconnected) throw new Error("Session is disconnected");

    let messageId: string | undefined;
    let last: EventOf<"assistant.message"> | undefined;
    let settle!: (outcome: { error?: Error; aborted?: boolean }) => void;
    const outcome = new Promise<{ error?: Error; aborted?: boolean }>((resolve) => (settle = resolve));
    const buffered: SessionEvent[] = [];
    const observe = (event: SessionEvent) => {
      if (event.agentId) return;
      if (event.type === "assistant.message" && event.data.originatingMessageId === messageId) last = event;
      else if (event.type === "session.error" && (!event.data.originatingMessageId || event.data.originatingMessageId === messageId)) {
        const error = new Error(event.data.message);
        if (event.data.stack) error.stack = event.data.stack;
        settle({ error });
      } else if (event.type === "session.idle") settle({ aborted: event.data.aborted === true });
    };
    const unsubscribe = this.on((event) => (messageId === undefined ? buffered.push(event) : observe(event)));
    let timer: NodeJS.Timeout | undefined;
    try {
      const timedOut = new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error(`Timeout after ${effectiveTimeout}ms waiting for session.idle`)), effectiveTimeout);
      });
      messageId = await this.send(options);
      buffered.splice(0).forEach(observe);
      const result = await Promise.race([outcome, timedOut]);
      if (result.error) throw result.error;
      if (schema) {
        if (result.aborted) throw new Error("The requested run was aborted before a structured result was completed.");
        if (!last || last.data.toolRequests?.length || !last.data.content.trim()) {
          throw new Error("The requested run completed without a structured assistant response.");
        }
        return schema.parse(JSON.parse(last.data.content));
      }
      if (result.aborted) throw new Error("The requested run was aborted before completion.");
      return last;
    } finally {
      clearTimeout(timer);
      unsubscribe();
    }
  }

  on(handler: (event: SessionEvent) => void): () => void {
    this.handlers.add(handler);
    return () => {
      this.handlers.delete(handler);
    };
  }

  async abort(): Promise<void> {
    await this.runtime.abortTurn(this.state);
  }

  async disconnect(): Promise<void> {
    this.markDisconnected();
    this.state.handles.delete(this);
  }

  async getEvents(): Promise<SessionEvent[]> {
    return [...this.state.events];
  }

  /** @internal */
  deliver(event: SessionEvent): void {
    if (this.disconnected) return;
    for (const handler of [...this.handlers]) {
      try {
        handler(event);
      } catch {
        // Like the SDK, listener failures never break the turn.
      }
    }
  }

  /** @internal */
  markDisconnected(): void {
    this.disconnected = true;
    this.handlers.clear();
  }
}
