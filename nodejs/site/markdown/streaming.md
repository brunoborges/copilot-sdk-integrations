---
title: Streaming
---

# Streaming

Every agent can stream a turn as typed events instead of returning the final text. Streams
power chat UIs, Server-Sent Events, interactive tool approvals, and the
[Vercel AI SDK integration](ai-sdk.md).

**In this chapter**

- [Event model](#event-model)
- [Streaming in core](#streaming-in-core)
<!-- only:nestjs -->
- [Server-Sent Events with NestJS](#server-sent-events-with-nestjs)
<!-- /only -->
- [Server-Sent Events with Express](#server-sent-events-with-express)
<!-- only:fastify -->
- [Server-Sent Events with Fastify](#server-sent-events-with-fastify)
<!-- /only -->
- [Global event listeners](#global-event-listeners)
- [useChat](#usechat)

## Event model

`CopilotAgentEvent` is a discriminated union on `type`:

| Event | Fields | Meaning |
| --- | --- | --- |
| `turn-start` | `sessionId` | The session was created or resumed. |
| `text-delta` | `delta`, `messageId?` | A chunk of the assistant's answer. |
| `reasoning-delta` | `delta` | A chunk of the model's reasoning, when the model emits it. |
| `message` | `content`, `messageId?` | A complete assistant message. |
| `tool-start` | `toolCallId`, `toolName`, `input` | A tool call started. |
| `tool-end` | `toolCallId`, `toolName`, `success`, `output?`, `error?` | A tool call finished. |
| `permission-request` | `requestId`, `request`, `respond(response)` | A policy returned `ask-user`; see [approvals](governance.md#ask-user-approvals). |
| `permission-resolved` | `requestId`, `approved` | The permission request was answered or timed out. |
| `usage` | `model?`, `inputTokens?`, `outputTokens?` | Token usage reported by the runtime. |
| `error` | `error` | The turn failed. Always the last event. |
| `done` | `text`, `sessionId` | The turn completed. Always the last event. |
| `raw` | `event` | Any other SDK `SessionEvent`, unmapped. |

Streams are **cold**: nothing runs until you iterate (or subscribe). Admission, timeouts, and
cancellation work exactly as for `send()`; see [conversations](conversations.md#turn-admission).
Failures are delivered as an `error` event rather than thrown from the iterator.

## Streaming in core

```ts
for await (const event of agent.stream({ prompt: "Summarize order 42", conversationId: "c1" })) {
  switch (event.type) {
    case "text-delta": process.stdout.write(event.delta); break;
    case "tool-start": console.log(`\n[${event.toolName}]`); break;
    case "error": console.error(event.error.message); break;
  }
}
```

Breaking out of the loop aborts the turn. A bound conversation streams with
`conversation.stream(prompt, options?)`.

<!-- only:nestjs -->
## Server-Sent Events with NestJS

Declare the method's return type as `Observable<CopilotAgentEvent>` (import `Observable` as a
value so the return type is in the decorator metadata, or set
`@UserMessage({ stream: "observable" })`), then map events with `toSseEvents()`:

```ts
import { Sse } from "@nestjs/common";
import { Observable } from "rxjs";
import type { CopilotAgentEvent } from "@copilot-sdk-integrations/agent-core";
import { toSseEvents } from "@copilot-sdk-integrations/nestjs";

@CopilotAgent({ name: "support" })
export class SupportAgent {
  @UserMessage()
  chat(@ConversationId() id: string, @Message() q: string): Observable<CopilotAgentEvent> {
    return implementedByCopilot();
  }
}

@Controller("support")
export class SupportController {
  constructor(private readonly support: SupportAgent) {}

  @Sse("chat")
  chat(@Query("id") id: string, @Query("q") q: string) {
    return this.support.chat(id, q).pipe(toSseEvents()); // event: <type>, data: JSON
  }
}
```

- Unsubscribing (the client disconnects) aborts the turn.
- `toMessageEvent(event)` maps one event to `{ type, data }`; `serializeAgentEvent(event)`
  returns the JSON view: `respond` is dropped and errors become `{ name, message }`.
- `observeAgentStream((signal) => iterable)` builds a cold Observable whose `signal` is aborted
  on unsubscribe; `fromAsyncIterable(iterable)` wraps an existing iterable, such as a core
  `agent.stream(...)`.
- For an `AsyncIterable` return type, set `@UserMessage({ stream: "async-iterable" })`.
<!-- /only -->

## Server-Sent Events with Express

```ts
app.get("/chat", async (req, res) => {
  const controller = new AbortController();
  res.on("close", () => controller.abort());
  res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" });
  for await (const event of agent.stream({ prompt: String(req.query.q), signal: controller.signal })) {
    if (event.type === "permission-request") continue; // or answer it
    const data = event.type === "error" ? { message: event.error.message } : event;
    res.write(`event: ${event.type}\ndata: ${JSON.stringify(data)}\n\n`);
  }
  res.end();
});
```

<!-- only:fastify -->
## Server-Sent Events with Fastify

The [Fastify plugin](fastify.md#streaming) adds `reply.copilotEvents()`, which does the above
plus heartbeats, `X-Accel-Buffering`, real HTTP statuses for turns that fail before the first
event, and abort on disconnect:

```ts
app.get<{ Querystring: { q: string } }>("/chat", async (request, reply) =>
  reply.copilotEvents(request.copilot.agent("support").stream({ prompt: request.query.q })),
);
```
<!-- /only -->

## Global event listeners

To observe every turn, for auditing, metrics, or usage accounting, register a listener. It
receives each event together with the turn's [`CopilotContext`](context-and-identity.md):

```ts
// Core
const copilot = createCopilot({
  onEvent: (event, context) => {
    if (event.type === "usage") metrics.record(context.principal, event.inputTokens, event.outputTokens);
  },
});
<!-- only:fastify -->

// Fastify: the same option, in the plugin's client options
await app.register(copilotPlugin, { client: { onEvent: (event, context) => { /* ... */ } } });
<!-- /only -->
<!-- only:nestjs -->

// NestJS
@Injectable()
export class UsageListener {
  @OnCopilotEvent("usage")
  onUsage(event: CopilotAgentEvent, context: CopilotContext) { /* ... */ }

  @OnCopilotEvent("*")
  onAny(event: CopilotAgentEvent) { /* ... */ }
}
<!-- /only -->
```

Listeners see events from both streaming and non-streaming calls. A failing listener is logged
and never breaks a turn.

<!-- only:nestjs -->
With `eventEmitter: true` (or `{ prefix: "ai" }`) in `CopilotModule.forRoot()` and
`EventEmitterModule.forRoot()` imported, every event is also re-emitted on `@nestjs/event-emitter`'s
`EventEmitter2` as `copilot.<type>` with `(event, context)`, so `@OnEvent("copilot.done")` works.
Bootstrap fails if the option is set but `EventEmitterModule` isn't imported.
<!-- /only -->

## useChat

For React, Vue, or Svelte chat UIs built with the AI SDK's `useChat`, don't hand-roll SSE:
return a UI message stream with `streamCopilotChat` from `@copilot-sdk-integrations/ai`. See
[Vercel AI SDK](ai-sdk.md).
