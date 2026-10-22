import type { CopilotAgentEvent } from "@copilot-sdk-integrations/core";
import type { MessageEvent } from "@nestjs/common";
import { Observable, map, type OperatorFunction } from "rxjs";

/**
 * A cold Observable over an async iterable created per subscription. `start` receives an
 * `AbortSignal` that is aborted when the subscriber unsubscribes, so the turn is cancelled promptly.
 */
export function observeAgentStream<T>(start: (signal: AbortSignal) => AsyncIterable<T>): Observable<T> {
  return new Observable<T>((subscriber) => {
    const controller = new AbortController();
    let iterator: AsyncIterator<T> | undefined;
    void (async () => {
      try {
        iterator = start(controller.signal)[Symbol.asyncIterator]();
        for (;;) {
          const result = await iterator.next();
          if (result.done) break;
          if (subscriber.closed) {
            await iterator.return?.();
            return;
          }
          subscriber.next(result.value);
        }
        subscriber.complete();
      } catch (error) {
        subscriber.error(error);
      }
    })();
    return () => {
      if (!controller.signal.aborted) controller.abort();
    };
  });
}

/** Converts any async iterable (for example `agent.stream(...)` from core) to an Observable. */
export function fromAsyncIterable<T>(iterable: AsyncIterable<T>): Observable<T> {
  return observeAgentStream(() => iterable);
}

/** A JSON-serializable view of an event (drops `respond`, flattens errors). */
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

/** Maps an event to a Nest SSE `MessageEvent` (`event: <type>`, JSON data). */
export function toMessageEvent(event: CopilotAgentEvent): MessageEvent {
  return { type: event.type, data: serializeAgentEvent(event) };
}

/** RxJS operator for `@Sse()` handlers: `return this.agent.stream(id, q).pipe(toSseEvents())`. */
export function toSseEvents(): OperatorFunction<CopilotAgentEvent, MessageEvent> {
  return map(toMessageEvent);
}
