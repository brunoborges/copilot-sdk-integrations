import type { ServerResponse } from "node:http";
import { createUIMessageStreamResponse, isToolUIPart, type UIMessage, type UIMessageChunk } from "ai";
import { getCopilotContext, type AgentHandle, type CopilotAgentEvent, type SendOptions } from "@copilot-sdk-integrations/core";
import { defaultApprovalStore, type ApprovalOwner, type CopilotApprovalStore } from "./approvals.js";
import {
  definedInit,
  pipeUIMessageStreamToResponse,
  pumpToStream,
  TurnPump,
  type ToUIMessageStreamOptions,
  type UIMessageStreamResponseInit,
} from "./ui-stream.js";

/** An approval answered in the UI (`addToolApprovalResponse`). */
export interface CopilotApprovalResponse {
  approvalId: string;
  toolCallId: string;
  approved: boolean;
  reason?: string;
}

/** What a `useChat` request asks the server to do. */
export interface CopilotChatRequest {
  /** Server-side conversation key (usually the `useChat` chat id). */
  conversationId?: string;
  /** Text of the latest user message, when the last message is a user message. */
  prompt?: string;
  /** Approval responses on the last assistant message, when the request continues a turn. */
  approvalResponses: CopilotApprovalResponse[];
  /** Id of the last assistant message, when the request continues a turn. */
  messageId?: string;
}

/**
 * Reads AI SDK `UIMessage[]` (as sent by `useChat`) into a Copilot turn request.
 *
 * Copilot keeps conversation history server-side (keyed by `conversationId`), so only the latest
 * user message is sent as the prompt; earlier messages are ignored. When the last message is an
 * assistant message, its tool parts in the `approval-responded` state are returned as approval
 * responses. Only text parts are read; files and data parts are ignored.
 */
export function fromUIMessages(messages: readonly UIMessage[], options: { conversationId?: string } = {}): CopilotChatRequest {
  const request: CopilotChatRequest = { approvalResponses: [] };
  if (options.conversationId !== undefined) request.conversationId = options.conversationId;
  const last = messages[messages.length - 1];
  if (!last) return request;
  if (last.role === "user") {
    const text = last.parts
      .filter((part): part is Extract<typeof part, { type: "text" }> => part.type === "text")
      .map((part) => part.text)
      .join("\n");
    if (text.trim() !== "") request.prompt = text;
    return request;
  }
  if (last.role === "assistant") {
    request.messageId = last.id;
    for (const part of last.parts) {
      if (!isToolUIPart(part) || part.state !== "approval-responded") continue;
      const response: CopilotApprovalResponse = {
        approvalId: part.approval.id,
        toolCallId: part.toolCallId,
        approved: part.approval.approved,
      };
      if (part.approval.reason !== undefined) response.reason = part.approval.reason;
      request.approvalResponses.push(response);
    }
  }
  return request;
}

export interface StreamCopilotChatOptions
  extends Omit<ToUIMessageStreamOptions, "messageId">,
    Pick<SendOptions, "tools" | "context" | "timeout"> {
  agent: AgentHandle;
  /** The `messages` of the `useChat` request body. */
  messages: readonly UIMessage[];
  /** Conversation key, usually the `useChat` chat `id`. Omit for an ephemeral turn. */
  conversationId?: string;
  /** Where suspended turns wait for approvals. Default: `defaultApprovalStore`. */
  approvals?: CopilotApprovalStore;
  /** Aborts the turn while it is streaming (a suspended turn is kept until answered or expired). */
  signal?: AbortSignal;
}

const EXPIRED_APPROVAL = "The approval request expired or is unknown to this server.";

/**
 * Runs a `useChat` request against a Copilot agent and returns the UI message chunk stream.
 *
 * - A new user message starts a turn (`conversationId` keeps history server-side) and supersedes
 *   any turn of that conversation still waiting for an approval.
 * - An "ask-user" permission becomes a `tool-approval-request`; the response then finishes while
 *   the turn stays suspended in `approvals`.
 * - The next request carrying the answers (`addToolApprovalResponse` +
 *   `sendAutomaticallyWhen: lastAssistantMessageIsCompleteWithApprovalResponses`) resumes the
 *   same turn and continues the same assistant message.
 */
export function streamCopilotChat(options: StreamCopilotChatOptions): ReadableStream<UIMessageChunk> {
  const { agent, messages, conversationId, approvals = defaultApprovalStore, signal, tools, context, timeout, ...streamOptions } = options;
  const request = fromUIMessages(messages, conversationId === undefined ? {} : { conversationId });
  // Approvals can only be answered (and suspended turns resumed) by the conversation and principal that started the turn.
  const owner: ApprovalOwner = { conversationId, principal: context?.principal ?? getCopilotContext()?.principal };

  if (request.approvalResponses.length > 0) {
    let pump: TurnPump | undefined;
    const prefix: UIMessageChunk[] = [];
    for (const response of request.approvalResponses) {
      const pending = approvals.get(response.approvalId, owner);
      if (!pending) {
        prefix.push({ type: "tool-output-error", toolCallId: response.toolCallId, errorText: EXPIRED_APPROVAL, ...dynamicFlag(streamOptions) });
        continue;
      }
      pending.respond(response.reason === undefined ? { approved: response.approved } : { approved: response.approved, feedback: response.reason });
      pump ??= approvals.take(pending.pump);
    }
    if (!pump) return staticStream(request.messageId, prefix, streamOptions);
    const resumed = pump;
    bindSignal(resumed, signal, approvals);
    return pumpToStream(resumed, {
      suspendOnApproval: true,
      prefix,
      onSuspend: () => approvals.suspend(resumed, owner),
    });
  }

  if (request.prompt === undefined) {
    return staticStream(request.messageId, [{ type: "error", errorText: "The request has no user message to send." }], streamOptions, "error");
  }

  const controller = new AbortController();
  const prompt = request.prompt;
  const sendOptions: Omit<SendOptions, "schema"> = { prompt, signal: controller.signal };
  if (conversationId !== undefined) sendOptions.conversationId = conversationId;
  if (tools !== undefined) sendOptions.tools = tools;
  if (context !== undefined) sendOptions.context = context;
  if (timeout !== undefined) sendOptions.timeout = timeout;
  const before = conversationId !== undefined ? approvals.abortConversation(owner) : Promise.resolve();
  const events = deferred(before, () => agent.stream(sendOptions));

  const pump: TurnPump = new TurnPump(events, {
    ...streamOptions,
    abort: () => controller.abort(),
    onApproval: (approval) => approvals.register(approval.approvalId, { respond: approval.respond, toolCallId: approval.toolCallId, pump, owner }),
    onResolved: (approvalId) => approvals.unregister(approvalId),
  });
  bindSignal(pump, signal, approvals);
  return pumpToStream(pump, {
    suspendOnApproval: true,
    onSuspend: () => approvals.suspend(pump, owner),
  });
}

/** `streamCopilotChat` as an SSE `Response` (Next.js route handlers and other fetch-style servers). */
export function createCopilotChatResponse(options: StreamCopilotChatOptions & UIMessageStreamResponseInit): Response {
  const { status, statusText, headers, keepAliveMs, consumeSseStream, ...chat } = options;
  return createUIMessageStreamResponse({
    stream: streamCopilotChat(chat),
    ...definedInit({ status, statusText, headers, keepAliveMs, consumeSseStream }),
  });
}

/** `streamCopilotChat` piped to a Node.js `ServerResponse` (Express, NestJS `@Res()`, plain `http`). */
export function pipeCopilotChatToResponse(
  response: ServerResponse,
  options: StreamCopilotChatOptions & UIMessageStreamResponseInit,
): Promise<void> {
  const { status, statusText, headers, keepAliveMs, consumeSseStream, ...chat } = options;
  return pipeUIMessageStreamToResponse(response, streamCopilotChat(chat), { status, statusText, headers, keepAliveMs, consumeSseStream });
}

function dynamicFlag(options: ToUIMessageStreamOptions): { dynamic?: true } {
  return options.dynamicTools ? { dynamic: true } : {};
}

function bindSignal(pump: TurnPump, signal: AbortSignal | undefined, approvals: CopilotApprovalStore): void {
  if (!signal) return;
  const onAbort = () => {
    // A turn suspended between requests outlives the request that started it.
    if (!approvals.isSuspended(pump)) void pump.close();
  };
  if (signal.aborted) onAbort();
  else signal.addEventListener("abort", onAbort, { once: true });
}

function staticStream(
  messageId: string | undefined,
  chunks: UIMessageChunk[],
  options: ToUIMessageStreamOptions,
  finishReason: "stop" | "error" = "stop",
): ReadableStream<UIMessageChunk> {
  return new ReadableStream<UIMessageChunk>({
    start(controller) {
      if (options.sendStart ?? true) controller.enqueue(messageId === undefined ? { type: "start" } : { type: "start", messageId });
      for (const chunk of chunks) controller.enqueue(chunk);
      if (options.sendFinish ?? true) controller.enqueue({ type: "finish", finishReason });
      controller.close();
    },
  });
}

/** An iterable that waits for `before` and then delegates to `make()` (lazily, on first `next`). */
function deferred(before: Promise<void>, make: () => AsyncIterable<CopilotAgentEvent>): AsyncIterable<CopilotAgentEvent> {
  return {
    [Symbol.asyncIterator]() {
      let inner: AsyncIterator<CopilotAgentEvent> | undefined;
      let closed = false;
      return {
        async next() {
          if (closed) return { done: true, value: undefined };
          if (!inner) {
            await before;
            if (closed) return { done: true, value: undefined };
            inner = make()[Symbol.asyncIterator]();
          }
          return inner.next();
        },
        async return(value?: unknown) {
          closed = true;
          if (inner?.return) return inner.return(value);
          return { done: true, value: undefined };
        },
      };
    },
  };
}
