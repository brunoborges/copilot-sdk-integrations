---
sites: fastify
title: Fastify plugin
---

# Fastify plugin

`@copilot-sdk-integrations/fastify` is a Fastify 5 plugin built on the
[core](core.md). It adds `fastify.copilot` and request-bound agent handles, request context for
tools and policies, HTTP error mapping, SSE streaming, AI SDK `useChat` routes with tool
approvals, health and info routes, and a testing helper for `app.inject()`. Agents and tools are
plain core definitions, so the same code runs under NestJS, Express, or Fastify.

**In this chapter**

- [Install](#install)
- [Register the plugin](#register-the-plugin)
- [Agents and typing](#agents-and-typing)
- [Request context](#request-context)
- [Errors](#errors)
- [Streaming](#streaming)
- [useChat and approvals](#usechat-and-approvals)
- [Health, info, and under-pressure](#health-info-and-under-pressure)
- [Lifecycle](#lifecycle)
- [Testing](#testing)

## Install

```sh
npm install @copilot-sdk-integrations/fastify @copilot-sdk-integrations/agent-core fastify zod
# for useChat routes (./ai):
npm install @copilot-sdk-integrations/ai ai
```

The package requires Fastify 5 and Node.js 20.19+ or 22.12+, and it's ESM-only. It has three
entry points:

| Entry point | Contents |
| --- | --- |
| `@copilot-sdk-integrations/fastify` | `copilotPlugin`, decorators, context hook, `copilotErrorHandler`, `reply.copilotEvents`, health and info routes, `copilotHealthCheck` |
| `@copilot-sdk-integrations/fastify/ai` | `copilotAiPlugin`: `reply.copilotChat()`, `reply.copilotUIStream()`, `fastify.copilotApprovals` |
| `@copilot-sdk-integrations/fastify/testing` | `buildCopilotTestApp()` and `parseSse()` |

## Register the plugin

Write the app as a factory that accepts the plugin's `client`, the usual Fastify pattern. Tests
then pass a scripted client (see [Testing](#testing)).

```ts
import Fastify from "fastify";
import { defineAgent, permissions, type Copilot, type CopilotOptions } from "@copilot-sdk-integrations/agent-core";
import { copilotPlugin } from "@copilot-sdk-integrations/fastify";

export const support = defineAgent({
  name: "support",
  instructions: "You help customers with their orders. Be concise.",
  tools: [lookupOrder],
  permissions: permissions.allowCustomTools(),
});

export async function buildApp({ client = {} }: { client?: CopilotOptions | Copilot } = {}) {
  const app = Fastify({ logger: true });
  await app.register(copilotPlugin, {
    client,                              // CopilotOptions, or an existing Copilot
    startup: "lazy",                     // "eager" starts the runtime in onReady
    agents: [support],
    context: { principal: (request) => request.user?.id },
    health: { path: "/health" },
  });

  app.post<{ Body: { question: string } }>("/ask", async (request) => ({
    answer: await request.copilot.agent("support").send({ prompt: request.body.question }),
  }));
  return app;
}
```

| Option | Default | Purpose |
| --- | --- | --- |
| `client` | `{}` | Core [`CopilotOptions`](configuration.md#copilotoptions), or an existing `Copilot` |
| `startup` | `"lazy"` | `"eager"` starts the runtime in `onReady`, so `ready()` and `listen()` fail when it can't start |
| `agents` | `[]` | Agents available by name |
| `agentOverrides` | `{}` | Per-agent overrides by name (model, provider, ...) |
| `context` | `{}` | [Request context](#request-context) options; `false` disables the hook |
| `errorHandler` | `true` | Installs the [error handler](#errors) |
| `health`, `info` | off | [Health and info routes](#health-info-and-under-pressure) |
| `shutdownGracePeriod` | `30_000` | How long `close()` waits for in-flight turns |
| `stopOnClose` | `true` when the plugin created the client | Stop the client in `onClose` |

Options are validated at registration; an invalid option fails `register` with a
`CopilotConfigurationError` that names the key. Logs go to the Fastify logger
(`fastify.log.child({ module: "copilot" })`) unless `client.logger` is set.

The plugin is wrapped with `fastify-plugin`, so its decorators, hooks, and error handler apply to
the scope you register it in. Register it once at the root for one client per app, or inside
each tenant's child plugin for one client per tenant.

## Agents and typing

- `fastify.copilot` is the core `Copilot` (`state`, `start()`, `stop()`, `health()`,
  `capacity()`, `client()`) plus `agent(name)`, `agents()`, `registerAgent(def)`, and `core`.
- `agent(name)` throws a `CopilotConfigurationError` listing the registered names when the name
  is unknown. `agent(definition)` returns a handle for any definition.
- `registerAgent(def)` adds an agent from a feature plugin before `ready()`. It throws on a
  duplicate name and after `ready()`.

Augment `CopilotAgentRegistry` to type agent names. When it has keys, `agent()` accepts only
those names:

```ts
declare module "@copilot-sdk-integrations/fastify" {
  interface CopilotAgentRegistry {
    support: typeof support;
  }
}

app.copilot.agent("support"); // ok
app.copilot.agent("suport");  // type error
```

## Request context

`request.copilot.agent(name)` returns a handle whose `send`, `stream`, and `conversation` calls
default `context` to the request's [context](context-and-identity.md) and `signal` to its
disconnect signal. Explicit arguments win.

| `context` option | Default | Purpose |
| --- | --- | --- |
| `principal(request)` | `request.user?.id ?? request.user?.sub` | Session-id derivation and permission ownership |
| `headers` | none | Headers copied into `context.attributes.headers` (MCP header functions, token resolvers) |
| `attributes(request)` | none | Extra attributes; `user` and `requestId` are always included |
| `abortOnDisconnect` | `true` | Abort `request.copilot` turns when the client disconnects before the response finishes |
| `hook` | `"preHandler"` | Stage that captures the context; the default runs after auth in `onRequest` or `preValidation` |

The rest of the request runs inside `runWithCopilotContext`, so tools, permission policies,
hooks, and `tokenResolver` see the request through `getCopilotContext()`:

```ts
const whoami = defineCopilotTool({
  name: "whoami",
  description: "The current user",
  skipPermission: true,
  execute: () => getCopilotContext()?.principal ?? "anonymous",
});
```

`request.copilot.context` and `request.copilot.signal` are also available directly, for example
in `onSend` hooks.

## Errors

The plugin's error handler maps Copilot errors to HTTP with Fastify's error body
(`{ statusCode, code, error, message }`), where `code` names the error, for example
`COPILOT_ADMISSION_REJECTED`:

| Error | Status |
| --- | --- |
| `CopilotAdmissionRejectedError` | `429` with `Retry-After` |
| `CopilotConversationBusyError`, `CopilotConversationExistsError` | `409` |
| `CopilotConversationNotFoundError` | `404` |
| `CopilotTimeoutError` | `504` |
| `CopilotAbortedError` | `499` (no body once the client is gone) |
| `CopilotRuntimeUnavailableError`, `CopilotShuttingDownError` | `503` |
| `CopilotSessionError`, `CopilotOutputValidationError` | `502` |
| Other Copilot errors | `500` with a generic message |

Other errors, such as route schema validation errors, go to Fastify's default handler
unchanged. Fastify allows one error handler per scope, so if the app has its own, pass
`errorHandler: false` and delegate:

```ts
import { CopilotIntegrationError } from "@copilot-sdk-integrations/agent-core";
import { copilotErrorHandler } from "@copilot-sdk-integrations/fastify";

app.setErrorHandler((error, request, reply) => {
  if (error instanceof CopilotIntegrationError) return copilotErrorHandler(error, request, reply);
  // ...
});
```

The same table is shared by every integration ([HTTP error mapping](operations.md#http-error-mapping)) and is
exported as `copilotErrorToHttp(error)`.

## Streaming

`reply.copilotEvents(events, options)` streams any `AsyncIterable<CopilotAgentEvent>` as
[Server-Sent Events](streaming.md): `event: <type>` and `data: <json>` frames, with
`Content-Type: text/event-stream`, `Cache-Control: no-cache`, and `X-Accel-Buffering: no`.

```ts
app.get<{ Querystring: { q: string } }>("/events", async (request, reply) =>
  reply.copilotEvents(request.copilot.agent("support").stream({ prompt: request.query.q }), { heartbeatMs: 15_000 }),
);
```

- Heartbeat comments are sent every `heartbeatMs` (default 15 s; `0` disables them).
- `copilotEvents` waits for the first event, so a turn that fails before streaming (admission
  rejected, runtime unavailable) goes to the error handler with a real status such as `429` or
  `503`. Later errors become a final `event: error` frame.
- `permission-request` events carry the `requestId` but not the `respond` callback.
- When the client disconnects, the request signal aborts the turn and the stream is cancelled.
  Give handles from `fastify.copilot.agent()` `signal: request.copilot.signal` yourself.

The body is a web `ReadableStream` sent through `reply.send()`, not `reply.hijack()`, so
`onSend` hooks, `@fastify/cors`, and `@fastify/compress` still apply.

For AI SDK front ends, `reply.copilotUIStream(events)` from the `./ai` entry point streams the
same events as an [AI SDK UI message stream](ai-sdk.md#ui-message-stream-mapping):

```ts
app.get<{ Querystring: { q: string } }>("/stream", async (request, reply) =>
  reply.copilotUIStream(request.copilot.agent("support").stream({ prompt: request.query.q })),
);
```

## useChat and approvals

Register `copilotAiPlugin` after `copilotPlugin`. `reply.copilotChat()` answers a `useChat`
request in one call:

```ts
import { copilotAiPlugin, type CopilotChatBody } from "@copilot-sdk-integrations/fastify/ai";

await app.register(copilotAiPlugin); // adds reply.copilotChat, reply.copilotUIStream, fastify.copilotApprovals

app.post<{ Body: CopilotChatBody }>("/api/chat", async (request, reply) =>
  reply.copilotChat({ agent: "support" }),
);
```

- `copilotChat({ agent, request?, conversationId?, approvals?, ... })` reads the `useChat` body
  (`{ id, messages }`, default `request.body`), runs the turn with the request's context and
  signal, and streams the UI message stream.
- **Conversations are scoped by principal.** The conversation id is `${principal}:${chatId}`
  (`"anonymous"` without a principal), so users can't resume each other's chats.
- **Approvals are owner-scoped.** `permissions.askUser()` and AI SDK `needsApproval` tools
  round-trip through [`addToolApprovalResponse`](ai-sdk.md#tool-approvals) using
  `fastify.copilotApprovals`, one in-memory store per app (pass `approvals` to
  `copilotAiPlugin` or `copilotChat` to replace it). Only the same principal, in the same
  conversation, can answer a suspended turn; anyone else gets the same "expired or unknown"
  tool error as an unknown id, and the turn keeps waiting.
- Pending approvals live in process memory. With several replicas, route each conversation to
  the same replica (sticky sessions).

## Health, info, and under-pressure

```ts
import underPressure from "@fastify/under-pressure";
import { copilotHealthCheck } from "@copilot-sdk-integrations/fastify";

await app.register(copilotPlugin, {
  health: { path: "/health/copilot" },
  info: { path: "/copilot/info", onRequest: app.auth([app.verifyAdmin]), includeInstructions: false },
});
await app.register(underPressure, { healthCheck: copilotHealthCheck(app), healthCheckInterval: 5_000 });
```

- **Health** (`health: true` uses `/health/copilot`) returns `fastify.copilot.health()`
  (`{ state, version, capacity }`), with `503` when the client is `failed`, `stopping`, or
  `stopped`. It never starts a lazy runtime.
- **Info** returns `{ state, version, capacity, agents }`, each agent described as in the
[info endpoint](operations.md#info-endpoint). It never includes keys or headers. Guard
  it with `onRequest`; the plugin logs a warning when there's no guard.
- **`@fastify/under-pressure`.** `copilotHealthCheck(app)` makes the Copilot state part of the
  app's existing health check and load shedding.
- Both routes are off by default, respect the `register` prefix, and have JSON response schemas.

## Lifecycle

| Fastify hook | Behaviour |
| --- | --- |
| `register` | Validate options, create the client (or adopt the given one) |
| `onReady` | With `startup: "eager"`, start the runtime; `ready()` / `listen()` reject when it can't start |
| `preClose` | Stop admitting turns (new turns get `503`) and start draining for up to `shutdownGracePeriod` |
| `onClose` | Wait for the drain, abort what's left, and stop the runtime (when `stopOnClose`) |

Crash restarts are the core's [runtime restarts](operations.md#runtime-restarts), unchanged.
Signal handling stays with the app: call `app.close()` on `SIGINT` and `SIGTERM`, by hand or
with `close-with-grace`:

```ts
const app = await buildApp();
await app.listen({ port: 3000 });

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, async () => {
    await app.close(); // drains turns, then stops the runtime
    process.exit(0);
  });
}
```

## Testing

`buildCopilotTestApp(build, script)` builds the app against the core's
[scripted runtime](testing.md#scripting-replies): it creates a `createTestCopilot` client,
passes it to your factory as `{ client }`, and stops it when the app closes. Call routes with
`app.inject()`; `parseSse()` splits SSE bodies into frames.

```ts
import { buildCopilotTestApp, parseSse } from "@copilot-sdk-integrations/fastify/testing";

it("answers through the agent", async () => {
  const { app, runtime } = await buildCopilotTestApp(
    (options) => buildApp(options),
    (rt) => rt.onPrompt(/order 42/).callTool("lookupOrder", { id: "42" }).thenReply("Order 42 shipped."),
  );
  const res = await app.inject({ method: "POST", url: "/ask", payload: { question: "Where is order 42?" } });
  expect(res.json()).toEqual({ answer: "Order 42 shipped." });
  expect(runtime.toolCalls).toMatchObject([{ name: "lookupOrder" }]);
  expect(parseSse((await app.inject("/events?q=hi")).body).at(-1)?.event).toBe("done");
  await app.close(); // stops the scripted client
});
```

There's no runtime process, no credentials, and no paid requests. The
[Fastify example](examples.md#fastify) tests every route this way, including a `useChat`
approval round trip.
