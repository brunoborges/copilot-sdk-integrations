---
sites: nestjs
title: Getting started
---

# Getting started

**In this chapter**

- [Install](#install)
- [Register the module](#register-the-module)
- [Write an agent](#write-an-agent)
- [Add a tool](#add-a-tool)
- [Call the agent from a controller](#call-the-agent-from-a-controller)
- [Runtime and credentials](#runtime-and-credentials)
- [Next steps](#next-steps)

## Install

The packages are not published yet. Build the workspace and link it, or depend on it from
a monorepo:

```sh
git clone https://github.com/brunoborges/copilot-sdk-integrations.git
cd copilot-sdk-integrations/nodejs
npm install
npm run build
```

In your application, the NestJS integration needs core, Zod, and the usual NestJS peers:

```sh
npm install @copilot-sdk-integrations/nestjs @copilot-sdk-integrations/agent-core zod
npm install @nestjs/common @nestjs/core reflect-metadata rxjs
```

Optional peers: `@nestjs/terminus` for the [health check](operations.md#health),
`@nestjs/event-emitter` for [event re-emission](streaming.md#global-event-listeners), and
`nestjs-cls` for the [CLS bridge](context-and-identity.md#nestjs-cls-and-other-context-stores).
The packages are ESM only. ESM apps import them directly. CommonJS apps, including the default
`nest new` setup, load them through `require(esm)`, which works without flags on Node.js
`^20.19.0 || >=22.12.0`.

Enable decorator metadata in `tsconfig.json`. Return-type inference for streaming methods
and constructor injection by type rely on it:

```json
{
  "compilerOptions": {
    "experimentalDecorators": true,
    "emitDecoratorMetadata": true
  }
}
```

## Register the module

Import `CopilotModule.forRoot()` or `forRootAsync()` once, in the root module. It is global,
so feature modules can register agents with `forFeature()`.

```ts
import { Module } from "@nestjs/common";
import { ConfigModule, ConfigService } from "@nestjs/config";
import { CopilotModule } from "@copilot-sdk-integrations/nestjs";

@Module({
  imports: [
    ConfigModule.forRoot(),
    CopilotModule.forRootAsync({
      inject: [ConfigService],
      useFactory: (config: ConfigService) => ({
        startup: "eager",               // start at bootstrap and fail fast (default: "lazy")
        limits: { maxConcurrentTurns: 16, maxQueuedTurns: 64 },
        agents: { support: { model: config.get("SUPPORT_MODEL") } },
      }),
    }),
    SupportModule,
  ],
})
export class AppModule {}
```

Enable shutdown hooks so `SIGTERM` drains in-flight turns before the runtime stops:

```ts
const app = await NestFactory.create(AppModule);
app.enableShutdownHooks();
await app.listen(3000);
```

Options are validated at bootstrap; unknown keys and invalid values throw a
`CopilotConfigurationError`. See [Configuration](configuration.md) for every option.

## Write an agent

An agent is an abstract class decorated with `@CopilotAgent`. Each `@UserMessage` method
becomes a Copilot turn. TypeScript does not allow decorators on abstract methods, so methods
are concrete stubs that `return implementedByCopilot()`; `forFeature()` replaces them.

```ts
import { z } from "zod";
import {
  ConversationId, CopilotAgent, Message, P, Returns, UserMessage, implementedByCopilot,
} from "@copilot-sdk-integrations/nestjs";
import { permissions } from "@copilot-sdk-integrations/agent-core";

const Order = z.object({ id: z.string(), status: z.enum(["shipped", "pending"]) });

@CopilotAgent({
  name: "support",
  instructions: "You are a support agent for an online store.",
  tools: [OrdersTools],
  permissions: permissions.allowCustomTools(),
})
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
}
```

Register the agent in a feature module. The agent class itself becomes an injectable provider:

```ts
@Module({
  imports: [CopilotModule.forFeature([SupportAgent])],
  providers: [OrdersTools, OrdersService],
  controllers: [SupportController],
})
export class SupportModule {}
```

## Add a tool

Tools are methods of ordinary providers, decorated with `@CopilotTool`. They run on the
DI-managed instance, so they can inject services.

```ts
import { Injectable } from "@nestjs/common";
import { CopilotTool, type CopilotToolInvocation } from "@copilot-sdk-integrations/nestjs";

@Injectable()
export class OrdersTools {
  constructor(private readonly orders: OrdersService) {}

  @CopilotTool({ description: "Look up an order by id", input: z.object({ id: z.string() }) })
  lookupOrder({ id }: { id: string }, invocation: CopilotToolInvocation) {
    return this.orders.find(id, invocation.context.principal);
  }
}
```

Custom tools are denied unless the agent has a permission policy;
`permissions.allowCustomTools()` above approves the agent's own tools. See
[Tool governance and hooks](governance.md).

## Call the agent from a controller

```ts
@Controller("support")
export class SupportController {
  constructor(private readonly support: SupportAgent) {}

  @Post(":conversationId")
  answer(@Param("conversationId") id: string, @Body("question") question: string) {
    return this.support.answer(id, question);
  }
}
```

The global `CopilotContextInterceptor` captures the authenticated user as the principal, so
the same conversation id from two users maps to two different sessions. The global
`CopilotExceptionFilter` maps Copilot errors to HTTP statuses (for example a full queue
to `429` with `Retry-After`). See [Operations](operations.md#http-error-mapping).

## Runtime and credentials

By default the integration spawns the Copilot runtime bundled with `@github/copilot-sdk`.
Point it elsewhere with `cliPath` (or the `COPILOT_CLI_PATH` environment variable), or
connect to a running runtime server with `cliUrl`.

Authentication, in order of precedence:

1. A per-user token from the request context or a [`CopilotTokenResolver`](context-and-identity.md#per-user-github-tokens).
2. `githubToken`, a service token for the whole client.
3. The logged-in Copilot CLI user (`useLoggedInUser`, the default when no token is configured).

For your own model keys instead of a Copilot subscription, configure a
[BYOK provider](agents.md#byok-providers).

## Next steps

- Stream replies to the browser with [Server-Sent Events](streaming.md) or [`useChat`](ai-sdk.md).
- Keep multi-turn [conversations](conversations.md) server-side.
- Write tests against the [scripted runtime](testing.md).
