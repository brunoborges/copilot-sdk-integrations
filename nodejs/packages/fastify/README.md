# @copilot-sdk-integrations/fastify

Fastify plugin for the GitHub Copilot SDK. It provides `fastify.copilot` and request-bound agent handles, request-context propagation to tools and policies, HTTP error mapping, SSE streaming, AI SDK `useChat` support, health and info routes, and a scripted testing helper. It's built on [`@copilot-sdk-integrations/core`](../core); see [`DESIGN-FASTIFY.md`](../../DESIGN-FASTIFY.md) for the full design.

```sh
npm install @copilot-sdk-integrations/fastify @copilot-sdk-integrations/core fastify zod
```

Requires Fastify 5 and Node.js 20.19+ or 22.12+. The package is ESM-only.

## Register the plugin

```ts
import Fastify from "fastify";
import { defineAgent } from "@copilot-sdk-integrations/core";
import { copilotPlugin } from "@copilot-sdk-integrations/fastify";

const support = defineAgent({ name: "support", model: "gpt-5", instructions: "You help customers." });

const app = Fastify({ logger: true });
await app.register(copilotPlugin, {
  client: { githubToken: process.env.GITHUB_TOKEN, limits: { maxConcurrentTurns: 16 } }, // CopilotOptions or an existing Copilot
  startup: "eager",                   // start in onReady; ready() rejects if the runtime can't start (default "lazy")
  agents: [support],
  agentOverrides: { support: { model: process.env.SUPPORT_MODEL } }, // merged over the definitions by name
  context: { headers: ["x-tenant"] }, // copied into context.attributes.headers
  health: true,                       // GET /health/copilot
  shutdownGracePeriod: 30_000,
});

app.post("/ask", async (request) => request.copilot.agent("support").send({ prompt: (request.body as { q: string }).q }));
```

Options are validated at registration; an invalid option fails `register` with a `CopilotConfigurationError` that names the key. Logs go to `fastify.log.child({ module: "copilot" })` unless `client.logger` is set.

The plugin is wrapped with `fastify-plugin`, so its decorators, hooks, and error handler apply to the scope you register it in. Register it once at the root for one client per app, or once inside each encapsulated child plugin for one client per tenant. `copilotPlugin.scoped` is the unwrapped plugin, which creates its own encapsulated scope.

## Agents and typing

- `fastify.copilot` is the core `Copilot` (`state`, `start()`, `stop()`, `health()`, `capacity()`, `client()`) plus `agent(name)`, `agents()`, and `registerAgent(def)`. `fastify.copilot.core` is the underlying core client.
- `agent(name)` throws a `CopilotConfigurationError` listing the registered names when the name is unknown. `agent(definition)` returns a handle for any definition.
- `registerAgent(def)` adds an agent from another plugin. It throws on a duplicate name and after `ready()`.

Augment `CopilotAgentRegistry` to type agent names:

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

`request.copilot.agent(name)` returns a handle whose `send`, `stream`, and `conversation` calls default `context` to the request's context and `signal` to its disconnect signal. Explicit arguments win.

| `context` option | Default | Purpose |
| --- | --- | --- |
| `principal(request)` | `request.user?.id ?? request.user?.sub` | Session-id derivation and permission ownership |
| `headers` | none | Headers copied into `context.attributes.headers` (MCP header functions, token resolvers) |
| `attributes(request)` | none | Extra attributes; `user` and `requestId` are always included |
| `abortOnDisconnect` | `true` | Abort `request.copilot` turns when the client disconnects before the response finishes |
| `hook` | `"preHandler"` | Stage that captures the context; the default runs after auth in `onRequest` or `preValidation` |

The rest of the request runs inside `runWithCopilotContext`, so tools, permission policies, hooks, and `tokenResolver` see the request through `getCopilotContext()`:

```ts
const whoami = defineCopilotTool({
  name: "whoami",
  description: "The current user",
  skipPermission: true,
  execute: () => getCopilotContext()?.principal ?? "anonymous",
});
```

`request.copilot.context` and `request.copilot.signal` are also available directly. Set `context: false` to skip the hook.

## Errors

The plugin sets an error handler that maps Copilot errors to HTTP with a Fastify-shaped body (`{ statusCode, code, error, message }`), for example `code: "COPILOT_ADMISSION_REJECTED"`.

| Error | Status |
| --- | --- |
| `CopilotAdmissionRejectedError` | 429 + `Retry-After` |
| `CopilotConversationBusyError`, `CopilotConversationExistsError` | 409 |
| `CopilotConversationNotFoundError` | 404 |
| `CopilotTimeoutError` | 504 |
| `CopilotAbortedError` | 499 (no body once the client is gone) |
| `CopilotRuntimeUnavailableError`, `CopilotShuttingDownError` | 503 |
| `CopilotSessionError`, `CopilotOutputValidationError` | 502 |
| other Copilot errors | 500 with a generic message |

Other errors (validation errors, your own errors) go to Fastify's default handler unchanged. If the app has its own error handler, pass `errorHandler: false` and delegate:

```ts
import { CopilotIntegrationError } from "@copilot-sdk-integrations/core";
import { copilotErrorHandler } from "@copilot-sdk-integrations/fastify";

app.setErrorHandler((error, request, reply) => {
  if (error instanceof CopilotIntegrationError) return copilotErrorHandler(error, request, reply);
  // ...
});
```

## Streaming

`reply.copilotEvents(events, options)` streams any `AsyncIterable<CopilotAgentEvent>` as Server-Sent Events: `event: <type>` and `data: <json>` frames, with `Content-Type: text/event-stream`, `Cache-Control: no-cache`, and `X-Accel-Buffering: no`.

```ts
app.get("/stream", async (request, reply) =>
  reply.copilotEvents(request.copilot.agent("support").stream({ prompt: "Hi" }), { heartbeatMs: 15_000 }),
);
```

- Heartbeat comments are sent every `heartbeatMs` (default 15 s; `0` disables them).
- `copilotEvents` returns a promise and waits for the first event, so a turn that fails before streaming (admission rejected, runtime unavailable) goes to the error handler with a real status such as `429` or `503`.
- An error thrown after the stream started becomes a final `event: error` frame; the message is generic unless it's a Copilot error.
- `permission-request` events carry the `requestId` but not the `respond` callback.
- When the client disconnects, the request signal aborts the turn (with request-bound handles) and the stream is cancelled.

The body is sent through `reply.send()`, so `onSend` hooks, compression, and CORS still apply.

## AI SDK `useChat`

```ts
import { copilotAiPlugin } from "@copilot-sdk-integrations/fastify/ai";

await app.register(copilotAiPlugin); // after copilotPlugin
app.post("/api/chat", async (request, reply) => reply.copilotChat({ agent: "support" }));
```

`reply.copilotChat({ agent, request?, approvals?, ... })` reads the `useChat` body (`{ id, messages }`, default `request.body`), runs the turn, and streams the AI SDK UI message stream.

- The conversation id is scoped by principal: `${principal}:${chatId}`, with `"anonymous"` when there's no principal.
- `permissions.askUser()` and AI SDK `needsApproval` tools round-trip through `addToolApprovalResponse` using `fastify.copilotApprovals`. Pass `approvals` to `copilotAiPlugin` or `copilotChat` to replace it. Only the request's principal can answer an approval from its own conversation; anyone else gets an "expired or unknown" tool error.

`reply.copilotUIStream(events, init)` streams any event iterable as a UI message stream. The `ai` and `@copilot-sdk-integrations/ai` packages are optional peer dependencies, needed only for this entry point.

## Health and info

```ts
import underPressure from "@fastify/under-pressure";
import { copilotHealthCheck } from "@copilot-sdk-integrations/fastify";

await app.register(copilotPlugin, {
  health: { path: "/health/copilot" },
  info: { path: "/copilot/info", onRequest: app.auth([app.verifyAdmin]), includeInstructions: false },
});
await app.register(underPressure, { healthCheck: copilotHealthCheck(app), healthCheckInterval: 5_000 });
```

- The health route returns `fastify.copilot.health()`, with 503 when the client is `failed`, `stopping`, or `stopped`. It never starts a lazy client. After a crash it reports 503 until the next turn restarts the runtime.
- The info route returns `{ state, version, capacity, agents }`, with each agent described by name, model, provider, tools, and MCP server names. It never includes keys, headers, or (by default) instructions. The plugin logs a warning when the route has no `onRequest` guard.
- Both routes are off by default and respect the `register` prefix.

## Testing

```ts
import { buildCopilotTestApp, parseSse } from "@copilot-sdk-integrations/fastify/testing";

const { app, runtime } = await buildCopilotTestApp(
  (options) => buildApp(options), // your app factory; pass options.client to copilotPlugin
  (rt) => rt.onPrompt(/order 42/).callTool("lookupOrder", { id: "42" }).thenReply("Order 42 shipped."),
);
const res = await app.inject({ method: "POST", url: "/ask", payload: { q: "Where is order 42?" } });
expect(runtime.toolCalls).toMatchObject([{ name: "lookupOrder" }]);
expect(parseSse((await app.inject("/stream")).body).at(-1)?.event).toBe("done");
await app.close(); // stops the scripted client
```

There's no runtime process, no credentials, and no paid requests.

## Lifecycle

- **Startup.** With `startup: "lazy"` (default) the first turn starts the runtime. With `"eager"`, `onReady` starts it and `ready()` / `listen()` reject when it can't start.
- **Shutdown.** `preClose` stops admission and starts draining: new turns get 503 while in-flight turns have up to `shutdownGracePeriod` to finish, then they're aborted. `onClose` waits for the drain and the runtime stop. Use `close-with-grace` or similar to call `app.close()` on signals.
- **Ownership.** `stopOnClose` defaults to `true` when the plugin created the client and `false` when you passed an existing `Copilot`.

## Limitations

- Pending tool approvals live in process memory. With several replicas, route each conversation to the same replica (sticky sessions).
- Fastify 5 only. With `copilotPlugin.scoped`, the decorators are visible only inside the plugin's own scope; to use a separate client per tenant from your routes, register `copilotPlugin` inside each tenant's child plugin.

## Documentation

- [`DESIGN-FASTIFY.md`](../../DESIGN-FASTIFY.md): the Fastify integration design.
- [`@copilot-sdk-integrations/core`](../core): agents, tools, permissions, conversations, and the scripted runtime.
- [`@copilot-sdk-integrations/ai`](../ai): AI SDK provider and `useChat` streaming.
