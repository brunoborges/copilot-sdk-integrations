import type { ServerResponse } from "node:http";
import {
  createUIMessageStreamResponse,
  generateId as aiGenerateId,
  pipeUIMessageStreamToResponse as aiPipeUIMessageStreamToResponse,
  type UIMessageChunk,
} from "ai";
import {
  CopilotIntegrationError,
  type CopilotAgentEvent,
  type PermissionRequest,
  type PermissionResponse,
} from "@copilot-sdk-integrations/agent-core";

/** Response options accepted by the AI SDK response helpers (status, headers, `keepAliveMs`, ...). */
export type UIMessageStreamResponseInit = Omit<Parameters<typeof createUIMessageStreamResponse>[0], "stream">;

type FinishReason = NonNullable<Extract<UIMessageChunk, { type: "finish" }>["finishReason"]>;

/** Token usage accumulated over a Copilot turn (sum of the runtime's per-model-call usage events). */
export interface CopilotUsage {
  inputTokens?: number;
  outputTokens?: number;
  totalTokens?: number;
}

/** Information passed to `messageMetadata` and used for the default finish metadata. */
export interface CopilotMessageMetadataInfo {
  part: "start" | "finish";
  sessionId?: string;
  model?: string;
  usage: CopilotUsage;
  finishReason?: FinishReason;
}

/** Default message metadata attached to the `finish` chunk. */
export interface CopilotMessageMetadata {
  sessionId?: string;
  model?: string;
  usage: CopilotUsage;
}

/** A pending "ask-user" permission, surfaced as an AI SDK tool approval request. */
export interface CopilotToolApproval {
  /** AI SDK approval id (the core permission request id). */
  approvalId: string;
  toolCallId: string;
  toolName: string;
  input: unknown;
  request: PermissionRequest;
  /** Answer the approval. Idempotent; later calls are ignored. */
  respond(response: PermissionResponse): void;
}

export interface ToUIMessageStreamOptions {
  /** Id of the assistant message. Default: a generated id. */
  messageId?: string;
  /** Send reasoning parts. Default: true. */
  sendReasoning?: boolean;
  /** Send the `start` chunk. Default: true. */
  sendStart?: boolean;
  /** Send the `finish` chunk. Default: true. */
  sendFinish?: boolean;
  /**
   * Emit tool parts as `dynamic-tool` parts instead of typed `tool-<name>` parts.
   * Default: false (typed parts, matching the names of AI SDK `tool()` objects given to the agent).
   */
  dynamicTools?: boolean;
  /**
   * Message metadata for the `start` and `finish` chunks. Default: no start metadata and
   * `{ sessionId, model, usage }` on finish.
   */
  messageMetadata?: (info: CopilotMessageMetadataInfo) => unknown;
  /**
   * Maps an error to the `errorText` sent to the client. Default: the message of core
   * `CopilotIntegrationError`s (timeouts, admission, session errors), otherwise "An error occurred.".
   */
  onError?: (error: unknown) => string;
  /**
   * Called for every "ask-user" permission after the `tool-approval-request` chunk is sent.
   * Answer it with `approval.respond(...)` (for example from a WebSocket message or a separate
   * endpoint). Unanswered approvals are denied by core when its `approvalWindow` elapses.
   */
  onApprovalRequest?: (approval: CopilotToolApproval) => void;
  /** Id generator for message and part ids. Default: the AI SDK `generateId`. */
  generateId?: () => string;
}

const GENERIC_ERROR = "An error occurred.";

export function defaultErrorText(error: unknown): string {
  return error instanceof CopilotIntegrationError ? error.message : GENERIC_ERROR;
}

type ChunkMapperOptions = Pick<ToUIMessageStreamOptions, "sendReasoning" | "dynamicTools" | "generateId">;

/**
 * Stateful mapping of `CopilotAgentEvent`s to AI SDK UI message chunks. One instance per turn;
 * it can span several HTTP responses when a turn is suspended for an approval.
 * @internal
 */
export class UIChunkMapper {
  sessionId: string | undefined;
  model: string | undefined;
  readonly usage: CopilotUsage = {};
  error: unknown;

  private readonly sendReasoning: boolean;
  private readonly dynamic: boolean;
  private readonly generateId: () => string;
  private text: { key: string | undefined; id: string } | undefined;
  private reasoning: { id: string } | undefined;
  private reasoningStreamed = false;
  private readonly streamedMessages = new Set<string>();
  /** Tool calls with a `tool-input-available` chunk; value: whether a terminal output chunk was sent. */
  private readonly tools = new Map<string, boolean>();
  /** Approval id -> tool call id (and whether the call is synthetic, i.e. not a real tool execution). */
  private readonly approvals = new Map<string, { toolCallId: string; synthetic: boolean }>();
  private readonly denied = new Set<string>();

  constructor(options: ChunkMapperOptions = {}) {
    this.sendReasoning = options.sendReasoning ?? true;
    this.dynamic = options.dynamicTools ?? false;
    this.generateId = options.generateId ?? aiGenerateId;
  }

  /** Maps one event. `approval` is set for permission requests (already emitted as chunks). */
  map(event: CopilotAgentEvent): { chunks: UIMessageChunk[]; approval?: Omit<CopilotToolApproval, "respond"> } {
    const out: UIMessageChunk[] = [];
    switch (event.type) {
      case "turn-start":
      case "done":
        this.sessionId = event.sessionId;
        break;
      case "text-delta": {
        if (event.delta === "") break;
        if (event.messageId !== undefined) this.streamedMessages.add(event.messageId);
        if (!this.text || this.text.key !== event.messageId) {
          this.closeParts(out);
          this.text = { key: event.messageId, id: this.generateId() };
          out.push({ type: "text-start", id: this.text.id });
        }
        out.push({ type: "text-delta", id: this.text.id, delta: event.delta });
        break;
      }
      case "message": {
        const streamed = event.messageId !== undefined ? this.streamedMessages.has(event.messageId) : this.text !== undefined;
        if (streamed) {
          if (this.text && (event.messageId === undefined || this.text.key === event.messageId)) this.closeText(out);
          break;
        }
        this.closeParts(out);
        const id = this.generateId();
        out.push({ type: "text-start", id }, { type: "text-delta", id, delta: event.content }, { type: "text-end", id });
        break;
      }
      case "reasoning-delta": {
        this.reasoningStreamed = true;
        if (!this.sendReasoning || event.delta === "") break;
        if (!this.reasoning) {
          this.closeParts(out);
          this.reasoning = { id: this.generateId() };
          out.push({ type: "reasoning-start", id: this.reasoning.id });
        }
        out.push({ type: "reasoning-delta", id: this.reasoning.id, delta: event.delta });
        break;
      }
      case "raw": {
        const raw = event.event as { type?: string; data?: { content?: unknown } };
        if (raw.type !== "assistant.reasoning") break;
        if (this.reasoning) {
          this.closeReasoning(out);
        } else if (!this.reasoningStreamed && this.sendReasoning && typeof raw.data?.content === "string" && raw.data.content !== "") {
          this.closeParts(out);
          const id = this.generateId();
          out.push({ type: "reasoning-start", id }, { type: "reasoning-delta", id, delta: raw.data.content }, { type: "reasoning-end", id });
        }
        this.reasoningStreamed = false;
        break;
      }
      case "tool-start":
        this.closeParts(out);
        this.ensureToolInput(out, event.toolCallId, event.toolName, event.input);
        break;
      case "tool-end": {
        if (this.tools.get(event.toolCallId) === true) break;
        this.closeParts(out);
        this.ensureToolInput(out, event.toolCallId, event.toolName, undefined);
        this.tools.set(event.toolCallId, true);
        if (this.denied.has(event.toolCallId)) {
          out.push({ type: "tool-output-denied", toolCallId: event.toolCallId });
        } else if (event.success) {
          out.push({ type: "tool-output-available", toolCallId: event.toolCallId, output: event.output ?? null, ...this.dynamicFlag() });
        } else {
          out.push({ type: "tool-output-error", toolCallId: event.toolCallId, errorText: event.error ?? "Tool execution failed", ...this.dynamicFlag() });
        }
        break;
      }
      case "permission-request": {
        this.closeParts(out);
        const request = event.request as PermissionRequest & { toolCallId?: string; toolName?: string; args?: unknown; intention?: string };
        const synthetic = typeof request.toolCallId !== "string" || request.toolCallId === "";
        const toolCallId = synthetic ? `permission-${event.requestId}` : request.toolCallId!;
        const toolName = typeof request.toolName === "string" && request.toolName !== "" ? request.toolName : `permission-${request.kind}`;
        const input = request.kind === "custom-tool" ? (request.args ?? {}) : stripKind(request);
        this.ensureToolInput(out, toolCallId, toolName, input);
        this.approvals.set(event.requestId, { toolCallId, synthetic });
        out.push({
          type: "tool-approval-request",
          approvalId: event.requestId,
          toolCallId,
          ...(typeof request.intention === "string" && request.intention !== "" ? { reason: request.intention } : {}),
        });
        return { chunks: out, approval: { approvalId: event.requestId, toolCallId, toolName, input, request: event.request } };
      }
      case "permission-resolved": {
        const approval = this.approvals.get(event.requestId);
        if (!approval) break;
        this.approvals.delete(event.requestId);
        out.push({ type: "tool-approval-response", approvalId: event.requestId, approved: event.approved });
        if (!event.approved) {
          this.denied.add(approval.toolCallId);
          if (this.tools.get(approval.toolCallId) === false) {
            this.tools.set(approval.toolCallId, true);
            out.push({ type: "tool-output-denied", toolCallId: approval.toolCallId });
          }
        } else if (approval.synthetic && this.tools.get(approval.toolCallId) === false) {
          this.tools.set(approval.toolCallId, true);
          out.push({ type: "tool-output-available", toolCallId: approval.toolCallId, output: { approved: true }, ...this.dynamicFlag() });
        }
        break;
      }
      case "usage":
        if (event.model !== undefined) this.model = event.model;
        if (event.inputTokens !== undefined) this.usage.inputTokens = (this.usage.inputTokens ?? 0) + event.inputTokens;
        if (event.outputTokens !== undefined) this.usage.outputTokens = (this.usage.outputTokens ?? 0) + event.outputTokens;
        if (this.usage.inputTokens !== undefined || this.usage.outputTokens !== undefined) {
          this.usage.totalTokens = (this.usage.inputTokens ?? 0) + (this.usage.outputTokens ?? 0);
        }
        break;
      case "error":
        this.error = event.error;
        this.closeParts(out);
        break;
    }
    return { chunks: out };
  }

  /** Closes open text and reasoning parts. */
  closeParts(out: UIMessageChunk[] = []): UIMessageChunk[] {
    this.closeText(out);
    this.closeReasoning(out);
    return out;
  }

  /** Closes open parts and settles tool calls that never produced an output. */
  finalize(out: UIMessageChunk[] = []): UIMessageChunk[] {
    this.closeParts(out);
    for (const [toolCallId, settled] of this.tools) {
      if (settled) continue;
      this.tools.set(toolCallId, true);
      out.push({ type: "tool-output-error", toolCallId, errorText: "The tool call did not complete", ...this.dynamicFlag() });
    }
    return out;
  }

  private ensureToolInput(out: UIMessageChunk[], toolCallId: string, toolName: string, input: unknown): void {
    if (this.tools.has(toolCallId)) return;
    this.tools.set(toolCallId, false);
    out.push({ type: "tool-input-available", toolCallId, toolName, input: input ?? {}, ...this.dynamicFlag() });
  }

  private dynamicFlag(): { dynamic?: true } {
    return this.dynamic ? { dynamic: true } : {};
  }

  private closeText(out: UIMessageChunk[]): void {
    if (!this.text) return;
    out.push({ type: "text-end", id: this.text.id });
    this.text = undefined;
  }

  private closeReasoning(out: UIMessageChunk[]): void {
    if (!this.reasoning) return;
    out.push({ type: "reasoning-end", id: this.reasoning.id });
    this.reasoning = undefined;
  }
}

function stripKind(request: PermissionRequest): Record<string, unknown> {
  const { kind: _kind, ...rest } = request as unknown as Record<string, unknown>;
  return rest;
}

/**
 * Pulls events from a Copilot turn and maps them to chunks. Keeps consuming the core iterator
 * (breaking out of it would abort the turn) and can outlive one HTTP response.
 * @internal
 */
export class TurnPump {
  readonly mapper: UIChunkMapper;
  readonly messageId: string;
  private readonly iterator: AsyncIterator<CopilotAgentEvent>;
  private finished = false;
  private closing: Promise<void> | undefined;

  constructor(
    events: AsyncIterable<CopilotAgentEvent>,
    readonly options: ToUIMessageStreamOptions & {
      /** Aborts the underlying turn (in addition to `iterator.return()`). */
      abort?: () => void;
      onApproval?: (approval: CopilotToolApproval) => void;
      onResolved?: (approvalId: string) => void;
    },
  ) {
    this.mapper = new UIChunkMapper(options);
    this.messageId = options.messageId ?? (options.generateId ?? aiGenerateId)();
    this.iterator = events[Symbol.asyncIterator]();
  }

  get done(): boolean {
    return this.finished;
  }

  /** Reads the next event. `suspend` is true after a permission request was emitted. */
  async step(): Promise<{ chunks: UIMessageChunk[]; done: boolean; suspend: boolean }> {
    if (this.finished) return { chunks: [], done: true, suspend: false };
    let result: IteratorResult<CopilotAgentEvent>;
    try {
      result = await this.iterator.next();
    } catch (error) {
      // Core streams report failures as events; this covers arbitrary iterables.
      this.finished = true;
      const chunks = this.mapper.map({ type: "error", error: error instanceof Error ? error : new Error(String(error)) }).chunks;
      return { chunks: [...chunks, ...this.errorChunk()], done: true, suspend: false };
    }
    if (result.done) {
      this.finished = true;
      return { chunks: [], done: true, suspend: false };
    }
    const event = result.value;
    const { chunks, approval } = this.mapper.map(event);
    if (event.type === "error") chunks.push(...this.errorChunk());
    if (event.type === "permission-resolved") this.options.onResolved?.(event.requestId);
    if (approval && event.type === "permission-request") {
      const full: CopilotToolApproval = { ...approval, respond: (response) => event.respond(response) };
      this.options.onApproval?.(full);
      this.options.onApprovalRequest?.(full);
      return { chunks, done: false, suspend: true };
    }
    return { chunks, done: false, suspend: false };
  }

  startChunk(): UIMessageChunk {
    const metadata = this.options.messageMetadata?.(this.metadataInfo("start"));
    return { type: "start", messageId: this.messageId, ...(metadata !== undefined ? { messageMetadata: metadata } : {}) };
  }

  finishChunk(finishReason: FinishReason): UIMessageChunk {
    const info = this.metadataInfo("finish", finishReason);
    const metadata = this.options.messageMetadata
      ? this.options.messageMetadata(info)
      : ({ sessionId: info.sessionId, model: info.model, usage: info.usage } satisfies CopilotMessageMetadata);
    return { type: "finish", finishReason, ...(metadata !== undefined ? { messageMetadata: metadata } : {}) };
  }

  /** Aborts the turn and releases the iterator. Idempotent. */
  close(): Promise<void> {
    if (!this.closing) {
      this.finished = true;
      this.options.abort?.();
      this.closing = Promise.resolve(this.iterator.return?.())
        .then(() => undefined)
        .catch(() => undefined);
    }
    return this.closing;
  }

  private errorChunk(): UIMessageChunk[] {
    const error = this.mapper.error;
    if (error === undefined) return [];
    return [{ type: "error", errorText: (this.options.onError ?? defaultErrorText)(error) }];
  }

  private metadataInfo(part: "start" | "finish", finishReason?: FinishReason): CopilotMessageMetadataInfo {
    const info: CopilotMessageMetadataInfo = { part, usage: { ...this.mapper.usage } };
    if (this.mapper.sessionId !== undefined) info.sessionId = this.mapper.sessionId;
    if (this.mapper.model !== undefined) info.model = this.mapper.model;
    if (finishReason !== undefined) info.finishReason = finishReason;
    return info;
  }
}

/**
 * Streams a pump as one UI message stream (one HTTP response).
 * With `suspendOnApproval`, the stream finishes after a `tool-approval-request` and `onSuspend` is
 * called with the still-running pump; otherwise it streams to the end of the turn.
 * @internal
 */
export function pumpToStream(
  pump: TurnPump,
  options: { suspendOnApproval?: boolean; onSuspend?: () => void; prefix?: UIMessageChunk[] } = {},
): ReadableStream<UIMessageChunk> {
  const sendStart = pump.options.sendStart ?? true;
  const sendFinish = pump.options.sendFinish ?? true;
  let ended = false;
  return new ReadableStream<UIMessageChunk>({
    start(controller) {
      if (sendStart) controller.enqueue(pump.startChunk());
      for (const chunk of options.prefix ?? []) controller.enqueue(chunk);
    },
    async pull(controller) {
      for (;;) {
        const { chunks, done, suspend } = await pump.step();
        for (const chunk of chunks) controller.enqueue(chunk);
        if (done) {
          ended = true;
          for (const chunk of pump.mapper.finalize()) controller.enqueue(chunk);
          if (sendFinish) controller.enqueue(pump.finishChunk(pump.mapper.error !== undefined ? "error" : "stop"));
          controller.close();
          return;
        }
        if (suspend && options.suspendOnApproval) {
          ended = true;
          for (const chunk of pump.mapper.closeParts()) controller.enqueue(chunk);
          if (sendFinish) controller.enqueue(pump.finishChunk("tool-calls"));
          controller.close();
          options.onSuspend?.();
          return;
        }
        if (chunks.length > 0) return;
      }
    },
    async cancel() {
      if (!ended) await pump.close();
    },
  });
}

/**
 * Maps a Copilot event stream to an AI SDK UI message chunk stream (`useChat` protocol).
 *
 * The stream consumes the whole turn; cancelling it (for example, the client disconnects) aborts
 * the turn. "ask-user" permissions become `tool-approval-request` chunks; answer them through
 * `onApprovalRequest`, or use `streamCopilotChat` for the `useChat` approval round trip.
 */
export function toUIMessageStream(
  events: AsyncIterable<CopilotAgentEvent>,
  options: ToUIMessageStreamOptions = {},
): ReadableStream<UIMessageChunk> {
  return pumpToStream(new TurnPump(events, options));
}

/** `toUIMessageStream` wrapped in an SSE `Response` (Next.js route handlers, Hono, Bun, ...). */
export function toUIMessageStreamResponse(
  events: AsyncIterable<CopilotAgentEvent>,
  options: ToUIMessageStreamOptions & UIMessageStreamResponseInit = {},
): Response {
  const { status, statusText, headers, keepAliveMs, consumeSseStream, ...streamOptions } = options;
  return createUIMessageStreamResponse({
    stream: toUIMessageStream(events, streamOptions),
    ...definedInit({ status, statusText, headers, keepAliveMs, consumeSseStream }),
  });
}

/** Alias of `toUIMessageStreamResponse`. */
export const createCopilotUIMessageStreamResponse = toUIMessageStreamResponse;

/**
 * Pipes a UI message chunk stream to a Node.js `ServerResponse` (Express, NestJS, plain `http`)
 * as Server-Sent Events. Positional form of the AI SDK helper.
 */
export function pipeUIMessageStreamToResponse(
  response: ServerResponse,
  stream: ReadableStream<UIMessageChunk>,
  init: UIMessageStreamResponseInit = {},
): Promise<void> {
  return aiPipeUIMessageStreamToResponse({ response, stream, ...definedInit(init) });
}

/** Maps Copilot events and pipes them to a Node.js `ServerResponse`. */
export function pipeCopilotEventsToResponse(
  response: ServerResponse,
  events: AsyncIterable<CopilotAgentEvent>,
  options: ToUIMessageStreamOptions & UIMessageStreamResponseInit = {},
): Promise<void> {
  const { status, statusText, headers, keepAliveMs, consumeSseStream, ...streamOptions } = options;
  return pipeUIMessageStreamToResponse(response, toUIMessageStream(events, streamOptions), {
    status,
    statusText,
    headers,
    keepAliveMs,
    consumeSseStream,
  });
}

/** @internal */
export function definedInit(init: UIMessageStreamResponseInit): UIMessageStreamResponseInit {
  return Object.fromEntries(Object.entries(init).filter(([, value]) => value !== undefined)) as UIMessageStreamResponseInit;
}
