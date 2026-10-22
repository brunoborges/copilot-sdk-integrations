import { CopilotIntegrationError } from "./errors.js";
import { copilotErrorCode } from "./http.js";
import type { CopilotAgentEvent } from "./types.js";

/** Default interval of SSE heartbeat comments. */
export const DEFAULT_SSE_HEARTBEAT_MS = 15_000;

export interface ToSseStreamOptions {
  /** Interval of `: heartbeat` comments that keep idle connections open through proxies. Default 15_000 ms; `0` disables them. */
  heartbeatMs?: number;
  /**
   * Message of the `error` frame sent when the iteration throws. Default: the message of a
   * `CopilotIntegrationError`, otherwise "An error occurred.".
   */
  onError?: (error: unknown) => string;
}

/** A JSON-serializable view of an event (drops the `respond` callback of `permission-request`, flattens errors). */
export function serializeAgentEvent(event: CopilotAgentEvent): Record<string, unknown> {
  switch (event.type) {
    case "permission-request": {
      const { respond: _respond, ...rest } = event;
      return rest;
    }
    case "error":
      return { type: "error", error: { name: event.error.name, message: event.error.message } };
    default:
      return { ...event };
  }
}

/** Formats one SSE frame: `event: <type>` and `data: <json>`. */
export function formatSseEvent(event: CopilotAgentEvent): string {
  return sseFrame(event.type, serializeAgentEvent(event));
}

function sseFrame(type: string, data: unknown): string {
  return `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
}

function defaultErrorMessage(error: unknown): string {
  return error instanceof CopilotIntegrationError ? error.message : "An error occurred.";
}

/**
 * Encodes Copilot events as a `text/event-stream` body (a web `ReadableStream` of UTF-8 bytes), for
 * Fastify's `reply.send()`, fetch-style `Response`s, or `Readable.fromWeb()` with Express and `http`.
 *
 * - Each event becomes `event: <type>` plus `data: <json>`; `permission-request` events keep their
 *   `requestId` but not the `respond` callback.
 * - Heartbeat comments are sent every `heartbeatMs` (default 15 s) while the stream is open.
 * - An error thrown by the iteration becomes a final `event: error` frame.
 * - Cancelling the stream (for example, the client disconnects) calls the iterator's `return()`,
 *   which aborts a core turn once its pending step settles. Pass the request's abort signal to the
 *   turn as well for an immediate abort.
 */
export function toSseStream(events: AsyncIterable<CopilotAgentEvent>, options: ToSseStreamOptions = {}): ReadableStream<Uint8Array> {
  const heartbeatMs = options.heartbeatMs ?? DEFAULT_SSE_HEARTBEAT_MS;
  if (typeof heartbeatMs !== "number" || Number.isNaN(heartbeatMs) || heartbeatMs < 0) {
    throw new RangeError("heartbeatMs must be a non-negative number");
  }
  const errorMessage = options.onError ?? defaultErrorMessage;
  const encoder = new TextEncoder();
  let iterator: AsyncIterator<CopilotAgentEvent> | undefined;
  let timer: ReturnType<typeof setInterval> | undefined;
  let closed = false;

  const finish = () => {
    closed = true;
    if (timer !== undefined) clearInterval(timer);
    timer = undefined;
  };

  return new ReadableStream<Uint8Array>({
    start(controller) {
      if (heartbeatMs > 0) {
        timer = setInterval(() => {
          if (closed) return;
          try {
            controller.enqueue(encoder.encode(": heartbeat\n\n"));
          } catch {
            finish();
          }
        }, heartbeatMs);
        timer.unref?.();
      }
    },
    async pull(controller) {
      try {
        iterator ??= events[Symbol.asyncIterator]();
        const result = await iterator.next();
        if (closed) return;
        if (result.done) {
          finish();
          controller.close();
          return;
        }
        controller.enqueue(encoder.encode(formatSseEvent(result.value)));
      } catch (error) {
        if (closed) return;
        finish();
        const err = error instanceof Error ? error : new Error(String(error));
        controller.enqueue(
          encoder.encode(sseFrame("error", { type: "error", error: { name: err.name, code: copilotErrorCode(err), message: errorMessage(error) } })),
        );
        controller.close();
      }
    },
    cancel() {
      finish();
      const current = iterator;
      // Not awaited: an async generator's return() waits for a pending next() to settle.
      if (current?.return) void Promise.resolve(current.return()).catch(() => undefined);
    },
  });
}
