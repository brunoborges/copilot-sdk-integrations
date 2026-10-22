---
title: Context and per-user identity
---

# Context and per-user identity

Every turn runs inside a `CopilotContext`, propagated with `AsyncLocalStorage` into tools,
permission policies, hooks, event listeners, MCP header resolvers, and session customizers.
It carries the caller's identity, which also scopes [conversations](conversations.md#session-ids)
and can supply a per-user GitHub token.

**In this chapter**

- [The context](#the-context)
- [Setting the context in core](#setting-the-context-in-core)
- [The NestJS context interceptor](#the-nestjs-context-interceptor)
- [nestjs-cls and other context stores](#nestjs-cls-and-other-context-stores)
- [Per-user GitHub tokens](#per-user-github-tokens)
- [Token precedence](#token-precedence)

## The context

| Field | Content |
| --- | --- |
| `principal` | The authenticated caller. Part of the session id. `undefined` for anonymous or system calls. |
| `conversationId` | Informational conversation id. Turns use the explicit `conversationId` argument, not this field. |
| `agentName` | The agent running the turn. |
| `sessionId` | The SDK session id, once the session is created or resumed. |
| `signal` | Aborted when the turn is cancelled. |
| `githubToken` | Per-user GitHub token for this turn, if any. |
| `attributes` | Free-form data: the request user, selected headers, tenant, trace ids, and so on. |

Read it anywhere downstream of a turn:

```ts
import { getCopilotContext } from "@copilot-sdk-integrations/core";

const audit = (action: string) => log.info({ action, user: getCopilotContext()?.principal });
```

Tool handlers also receive it as `invocation.context`, and policies as `ctx.context`.

## Setting the context in core

```ts
import { bindCopilotContext, runWithCopilotContext } from "@copilot-sdk-integrations/core";

app.post("/chat", (req, res, next) =>
  runWithCopilotContext({ principal: req.user.id, attributes: { tenant: req.user.tenant } }, () =>
    agent.send({ prompt: req.body.prompt, conversationId: req.body.id }).then((text) => res.json({ text }), next),
  ),
);
```

- `runWithCopilotContext(partial, fn)` merges `partial` into the current context (attributes
  are merged too) and runs `fn` in it.
- `bindCopilotContext(fn)` captures the current context and returns a function that restores it,
  for callbacks that escape `AsyncLocalStorage` (some event emitters and pools).
- The `context` send option merges extra fields for one turn; the `principal` of a bound
  conversation wins over both.

## The NestJS context interceptor

`CopilotModule` registers a global `CopilotContextInterceptor` for HTTP handlers. It runs after
guards, so a Passport guard has already set `request.user`:

| Context field | Source |
| --- | --- |
| `principal` | `context.principal(req)` if configured; otherwise `request.user` when it is a string, or its `id`, `sub`, `username`, `login`, or `email`. |
| `conversationId` | `context.conversationId(req)`, if configured. Informational: agent calls use their `@ConversationId()` argument, and calls without one stay ephemeral. |
| `attributes.user` | `request.user`. |
| `attributes.headers` | The headers listed in `context.headers`, keyed by lower-case name. |
| other attributes | `context.attributes(req)`, merged last. |
| `signal` | Aborted when the response closes before it finishes, unless `abortOnDisconnect: false`. |

```ts
CopilotModule.forRoot({
  context: {
    headers: ["x-tenant", "traceparent"],
    principal: (req) => req.user?.email,
    attributes: (req) => ({ tenant: req.headers["x-tenant"] }),
  },
});
```

Set `context: false` to disable the interceptor (for example, to set the context yourself).
The helpers `copilotContextFromRequest(req, options)` and `defaultPrincipal(req)` are exported
for WebSocket gateways, microservices, and other non-HTTP transports, where the interceptor does
nothing.

## nestjs-cls and other context stores

The interceptor only covers HTTP handlers. When the app uses
[`nestjs-cls`](https://papooch.github.io/nestjs-cls/), import `CopilotClsModule` from the
`/cls` subpath (the main entry point never imports `nestjs-cls`, an optional peer dependency):

```ts
import { CopilotClsModule } from "@copilot-sdk-integrations/nestjs/cls";

@Module({
  imports: [
    ClsModule.forRoot({ global: true, middleware: { mount: true } }),
    CopilotModule.forRoot({}),
    CopilotClsModule.forRoot({
      // Optional. The default reads the CLS keys "principal" (or "user") and "conversationId".
      context: (cls) => ({ principal: cls.get("userId"), attributes: { tenant: cls.get("tenant") } }),
    }),
  ],
})
export class AppModule {}
```

- **Any entry point.** The context is read from CLS whenever a declarative agent method,
  `CopilotAgents.handle()`, or an `@InjectAgent()` handle is called: HTTP, microservice handlers,
  queue consumers, and cron jobs alike. CLS values win over the interceptor's; an explicit
  `context` send option wins over both.
- **Propagation.** The CLS store active at call time is restored around `@CopilotTool`
  methods, hooks, `@OnCopilotEvent` listeners, injectable permission policies, and event-emitter
  re-emission, so `ClsService.get()` works inside them. Set `propagate: false` to turn this off.
- **Custom stores.** `CopilotClsModule` provides the generic `COPILOT_CONTEXT_BRIDGE` token, a
  `CopilotContextBridge` with `capture()` and an optional `restore(context, fn)`. Provide your
  own bridge (or an array of bridges) to feed the context from another store.

## Per-user GitHub tokens

By default, every turn uses the client's credentials (a service token or the logged-in CLI
user). To bill and authorize each request as the end user, resolve the user's GitHub token
per turn.

Core: pass `tokenResolver` to `createCopilot`, or set `context.githubToken` directly:

```ts
const copilot = createCopilot({
  tokenResolver: (ctx) => (ctx.attributes.user as { accessToken?: string } | undefined)?.accessToken,
});
```

NestJS: provide a `CopilotTokenResolver`. `PassportGitHubTokenResolver` reads the token that a
Passport GitHub strategy stored on `request.user` (`accessToken`, `githubToken`, or
`github.accessToken`):

```ts
@Module({
  imports: [CopilotModule.forRoot({})],
  providers: [{ provide: CopilotTokenResolver, useClass: PassportGitHubTokenResolver }],
})
export class AppModule {}
```

The Passport verify callback must keep the access token on the user object. For other
identity providers, extend `CopilotTokenResolver` and implement `resolve(context)` (sync or
async). A module-level `tokenResolver` option wins over the provider.

## Token precedence

For each turn:

1. `context.githubToken`, if already set (for example by your own middleware or the `context` send option).
2. Otherwise, the token resolver's result, raced against the turn's abort signal.
3. When connected to an external runtime (`cliUrl`), the client's `githubToken` as a fallback.

The resolved token becomes the session's `gitHubToken` unless `configureSession` already set
one. When spawning a local runtime, the client's `githubToken` is used for the whole client.
Combine per-user tokens with context-derived
[MCP headers](agents.md#mcp-servers) to call the GitHub MCP server as the user.
