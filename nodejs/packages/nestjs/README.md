# @copilot-sdk-integrations/nestjs

NestJS module for the GitHub Copilot SDK. It provides declarative agents, `@CopilotTool` providers, request-context propagation, per-user tokens, HTTP error mapping, SSE streaming, a Terminus health indicator, and a scripted testing module. It's built on [`@copilot-sdk-integrations/core`](../core); see [`DESIGN.md`](../../DESIGN.md) §4–§15 for the full design.

```sh
npm install @copilot-sdk-integrations/nestjs @copilot-sdk-integrations/core zod
```

## Register the module

```ts
@Module({
  imports: [
    CopilotModule.forRootAsync({
      inject: [ConfigService],
      useFactory: (config: ConfigService) => ({
        startup: "eager",                       // start at bootstrap and fail fast (default: "lazy")
        limits: { maxConcurrentTurns: 16, maxQueuedTurns: 64 },
        shutdownGracePeriod: 30_000,
        providers: { foundry: { type: "azure", baseUrl: config.get("FOUNDRY_URL")!, apiKey: config.get("FOUNDRY_KEY") } },
        agents: { support: { model: config.get("SUPPORT_MODEL") } }, // per-agent overrides beat decorators
        context: { headers: ["x-tenant"] },     // copied into CopilotContext.attributes.headers
      }),
    }),
    SupportModule,
  ],
})
export class AppModule {}
```

`CopilotModule.forRoot()` and `forRootAsync()` register a global module, which `forFeature()` relies on. The module provides:

- `COPILOT`, the core `Copilot`, injected with `@InjectCopilot()`.
- `COPILOT_CLIENT`, an accessor `() => Promise<client>` that starts a lazy runtime, injected with `@InjectCopilotClient()`.
- `CopilotAgents`.
- `CopilotHealthIndicator`.
- A global `CopilotContextInterceptor` (disable it with `context: false`).
- A global `CopilotExceptionFilter` (disable it with `exceptionFilter: false`).

Invalid options throw a `CopilotConfigurationError` at bootstrap. Call `app.enableShutdownHooks()` so `SIGTERM` drains in-flight turns before the runtime stops.

## Declarative agents

```ts
const Order = z.object({ id: z.string(), status: z.enum(["shipped", "pending"]) });

@CopilotAgent({ name: "support", instructions: "You are a support agent.", tools: [OrdersTools], permissions: permissions.allowCustomTools() })
export abstract class SupportAgent {
  @UserMessage()
  answer(@ConversationId() conversationId: string, @Message() question: string): Promise<string> {
    return implementedByCopilot();
  }

  @UserMessage("Extract the order from: {{text}}")
  @Returns(Order)
  extract(@P("text") text: string): Promise<z.infer<typeof Order>> {
    return implementedByCopilot();
  }

  @UserMessage()
  chat(@ConversationId() id: string, @Message() q: string, @Signal() signal?: AbortSignal): Observable<CopilotAgentEvent> {
    return implementedByCopilot();
  }
}

@Module({ imports: [CopilotModule.forFeature([SupportAgent])], providers: [OrdersTools, OrdersService] })
export class SupportModule {}
```

Agent classes can be `abstract`, but their methods must be concrete stubs. TypeScript doesn't allow decorators on abstract methods. The stubs `return implementedByCopilot();`, and `forFeature` overrides them with the generated implementation.

Return types:

- **`Promise`**: resolves with the reply text, or with the parsed value when the method has `@Returns(zod)`.
- **`Observable<CopilotAgentEvent>`**: a cold stream. Unsubscribing aborts the turn. The return type is inferred from decorator metadata, so import `Observable` as a value, or set `@UserMessage({ stream: "observable" })`.
- **`AsyncIterable`**: set `@UserMessage({ stream: "async-iterable" })`.

`@UserMessageFrom(path)` loads the template from a file at bootstrap. Relative paths resolve against the `templatesDir` module option, which defaults to `process.cwd()`. Absolute paths and `file:` URLs are used as is, so a template that sits next to its source file can be written as `@UserMessageFrom(new URL("./prompts/triage.md", import.meta.url))`. In a compiled app, copy the template files into the build output, or point `templatesDir` at the source folder.

Template rules are checked at bootstrap:

- Each `{{placeholder}}` must be bound by `@P(name)`, or by `{{message}}` for `@Message()`.
- Without a template, exactly one `@Message()` is required.
- `@Tools(...)` on the class or a method adds tool sources.

Conversations: `CopilotAgents` gives bound instances.

```ts
agents.create(SupportAgent, "c1").answer("Hi");  // create-or-resume; the @ConversationId arg is omitted
agents.bind(SupportAgent, "c1").answer("More");  // resume-only → CopilotConversationNotFoundError (404)
agents.respondToPermission("c1", requestId, true); // answer an "ask-user" permission-request event
```

`respondToPermission` only accepts an answer from the principal that started the turn (taken from the
request's Copilot context, or `{ principal }` as a fourth argument); otherwise it returns `false`.

Functional agents: `CopilotModule.forFeature([defineAgent({...})])`, then inject the core `AgentHandle` with `@InjectAgent("name")`.

## Tools, permissions, hooks, events

```ts
@Injectable()
@UseToolInterceptors(AuditInterceptor)
export class OrdersTools {
  constructor(private readonly orders: OrdersService) {}

  @CopilotTool({ description: "Look up an order", input: z.object({ id: z.string() }) })
  lookupOrder({ id }: { id: string }, invocation: CopilotToolInvocation) {
    return this.orders.find(id, invocation.context.principal);
  }
}
```

- **Tool providers**: tools run on the DI instance. Request-scoped and transient providers are resolved once per turn. A provider must be registered in some module.
- **Permissions**: custom tools are denied unless the agent has a policy. A policy can be:
  - a `permissions.*` function
  - an injectable class with `evaluate(request, ctx)`
  - an instance
  - an app-wide default provided as `COPILOT_PERMISSION_POLICY`
- **Hooks**: `@CopilotHook("preToolUse", { agents: [SupportAgent] })` methods are composed with each agent's `hooks`. Global hooks run first.
- **Events**: `@OnCopilotEvent("done" | "tool-end" | "*")` methods receive `(event, context)`. Listener failures are logged and never break a turn.
- **`@nestjs/event-emitter`**: set `eventEmitter: true` (or `{ prefix: "ai" }`) in `forRoot` and import `EventEmitterModule.forRoot()`. Every agent event is then re-emitted on `EventEmitter2` as `copilot.<type>` (for example `copilot.done`, `copilot.tool-end`, `copilot.permission-request`) with `(event, context)`, so `@OnEvent("copilot.done")` works. Bootstrap fails if the option is set but `EventEmitterModule` isn't imported. Emitter failures are logged and never break a turn. The package is an optional peer dependency, loaded only when the option is set.
- **MCP servers with per-user credentials**: an MCP server's `headers` (HTTP) or `env` (stdio) values may be functions of the `CopilotContext`, or the whole map may be one function. They're resolved for every session create and resume, after the token resolver runs, so `ctx.githubToken` is available. Entries that resolve to `undefined` are dropped.

  ```ts
  mcpServers: {
    github: {
      type: "http",
      url: "https://api.githubcopilot.com/mcp/",
      headers: { Authorization: (ctx) => ctx.githubToken && `Bearer ${ctx.githubToken}` },
    },
  }
  ```
- **Customizers**: provide `COPILOT_CLIENT_CUSTOMIZER` and `COPILOT_SESSION_CUSTOMIZER` (a function or an array) to adjust the raw SDK options.

## Request context and per-user tokens

The interceptor runs each HTTP handler inside `runWithCopilotContext`, setting:

- **`principal`**: `request.user` as a string, or its `id`/`sub`/`username`/`login`/`email`, or the result of `context.principal(req)`.
- **`attributes.user`** and **`attributes.headers`** (the configured headers).
- **`signal`**: aborted when the client disconnects.

Tools, policies, and hooks read this context with `getCopilotContext()`.

To use per-user GitHub tokens, provide a `CopilotTokenResolver`:

```ts
providers: [{ provide: CopilotTokenResolver, useClass: PassportGitHubTokenResolver }] // reads request.user.accessToken
```

### `nestjs-cls`

When the app uses [`nestjs-cls`](https://papooch.github.io/nestjs-cls/), import `CopilotClsModule` from the `/cls` subpath. The main entry point never imports `nestjs-cls`.

```ts
import { CopilotClsModule } from "@copilot-sdk-integrations/nestjs/cls";

@Module({
  imports: [
    ClsModule.forRoot({ global: true, middleware: { mount: true } }),
    CopilotModule.forRoot({ /* ... */ }),
    CopilotClsModule.forRoot({
      // Optional. The default reads the CLS keys "principal" (or "user") and "conversationId".
      context: (cls) => ({ principal: cls.get("userId"), attributes: { tenant: cls.get("tenant") } }),
    }),
  ],
})
export class AppModule {}
```

- **Any entry point**: the context is read from CLS whenever a declarative agent method, `CopilotAgents.handle()`, or `@InjectAgent()` handle is called. This covers HTTP, microservice handlers, queues, and cron jobs alike. CLS values win over the interceptor's; explicit `context` passed to `send()`/`stream()` wins over both.
- **Propagation**: the CLS store active at call time is restored around `@CopilotTool` methods, hooks, `@OnCopilotEvent` listeners, injectable permission policies, and `eventEmitter` re-emission. These run on SDK callbacks outside the caller's async context, so `ClsService.get()` still works inside them. Set `propagate: false` to turn this off.
- **Custom sources**: `CopilotClsModule` is a provider of the generic `COPILOT_CONTEXT_BRIDGE` token (`{ capture(), restore?(ctx, fn) }`). Provide your own bridge to feed the context from another store.

## HTTP errors, streaming, health

`CopilotExceptionFilter` maps core errors to HTTP statuses:

| Error | Status |
| --- | --- |
| admission rejected | 429 + `Retry-After` |
| conversation busy or exists | 409 |
| conversation not found | 404 |
| timeout | 504 |
| aborted | 499 |
| runtime unavailable or shutting down | 503 |
| session or output validation | 502 |
| other | 500, with a generic message |

`copilotErrorToHttp()` exposes the same mapping.

```ts
@Sse("chat")
chat(@Query("q") q: string) {
  return this.support.chat("c1", q).pipe(toSseEvents()); // event: <type>, data: JSON
}

@Get("health") @HealthCheck()
check() {
  return this.health.check([() => this.copilotHealth.isHealthy("copilot")]);
}
```

The health check never starts a lazy runtime. It reports the client state, runtime version, and admission capacity, and returns `down` when the runtime is stopping, stopped, or failed. `@nestjs/terminus` is an optional peer dependency; the indicator doesn't import it.

### Info endpoint

`CopilotInfoModule` is opt-in and not registered by `CopilotModule`. It exposes a read-only `GET /copilot/info` endpoint, the counterpart of the Spring Actuator info endpoint:

```ts
imports: [CopilotInfoModule.register({ path: "admin/copilot", guards: [AdminGuard] })]
```

The response contains the runtime `state`, `version` (when running), `capacity` (including `activeTurns`), and the registered agents: name, description, model, reasoning effort, provider name, custom tool names, MCP server names, and tool filters. Provider keys, MCP headers and env, and instructions are never included. Set `includeInstructions: true` to include instructions. Reading the endpoint never starts a lazy runtime. Inject `CopilotInfoService` to build the same document yourself.

## Testing

```ts
const moduleRef = await Test.createTestingModule({
  imports: [
    CopilotTestingModule.forRoot((runtime) =>
      runtime.onPrompt(/order 42/).callTool("lookupOrder", { id: "42" }).thenReply("Order 42 shipped.")),
    CopilotModule.forFeature([SupportAgent]),
  ],
  providers: [OrdersTools, { provide: OrdersService, useValue: fakeOrders }],
}).compile();

await expect(moduleRef.get(SupportAgent).answer("c1", "Where is order 42?")).resolves.toBe("Order 42 shipped.");
expect(moduleRef.get(CopilotTestRuntime).prompts).toEqual(["Where is order 42?"]);
```

`CopilotTestingModule` lives in `@copilot-sdk-integrations/nestjs/testing`. It also offers `forRootAsync`. It replaces the runtime with core's scripted `CopilotTestRuntime`, so tests need no process, no credentials, and make no paid requests. Inject the `CopilotTestRuntime` to script replies and inspect its recordings.

Test runners must emit decorator metadata (`emitDecoratorMetadata`) for constructor injection by type. With Vitest, use `unplugin-swc`. The library itself uses explicit `@Inject` tokens.
