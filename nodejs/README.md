# Copilot for Node.js

Status: **in development, not published**.

Integrations for the
[Copilot SDK for Node.js/TypeScript](https://github.com/github/copilot-sdk/tree/main/nodejs):

- **core**: a framework-agnostic layer for client lifecycle, typed agents, tools,
  governance (deny-by-default permissions), conversations, and a scripted test runtime.
  Usable from Express, Hono, or plain Node.js.
- **nestjs**: a `CopilotModule` modeled on the [Spring Boot starter](../java/spring-boot/),
  with `@CopilotAgent` classes, `@CopilotTool` provider methods, Terminus health, and a
  testing module.
- **fastify**: a Fastify 5 plugin with `fastify.copilot` and request-bound agents, a
  request-context hook, HTTP error mapping, SSE, AI SDK `useChat` routes with approvals,
  health and info routes, and an `app.inject()` testing helper.
- **ai**: an [AI SDK](https://ai-sdk.dev) adapter, so `useChat` front ends work against
  a Copilot backend.

```ts
@CopilotAgent({ name: "support", instructions: "Help customers with orders." })
export abstract class SupportAgent {
  @UserMessage()
  answer(@ConversationId() id: string, @Message() question: string): Promise<string> {
    return implementedByCopilot(); // the module generates the implementation
  }
}
```

The packages are ESM-only; CommonJS apps load them with `require()` through Node's
`require(esm)` support.

## Packages

| Package | Purpose |
| --- | --- |
| [`@copilot-sdk-integrations/core`](packages/core/) | Client lifecycle, typed agents, tools, governance, conversations, and the scripted test runtime |
| [`@copilot-sdk-integrations/ai`](packages/ai/) | AI SDK provider and UI message stream adapter |
| [`@copilot-sdk-integrations/nestjs`](packages/nestjs/) | `CopilotModule`, decorators, Terminus health indicator, and testing module |
| [`@copilot-sdk-integrations/fastify`](packages/fastify/) | Fastify plugin: decorators, context hook, error handler, SSE, `useChat` routes (`./ai`), and `buildCopilotTestApp` (`./testing`) |

## Examples

| Example | Shows |
| --- | --- |
| [`examples/nestjs-chat`](examples/nestjs-chat/) | NestJS app with a React `useChat` UI, tool approvals, and Terminus health |
| [`examples/fastify`](examples/fastify/) | Fastify 5 app on the plugin: route schemas, SSE, `useChat` approvals, `app.inject()` tests |
| [`examples/express`](examples/express/) | Plain Express 5 app using only the core and the AI SDK adapter |

## Building

Install Node.js `^20.19 || >=22.12`. The packages live in an npm workspace.

```sh
cd nodejs
npm install
npm run build
npm test
```

Tests use the scripted test runtime; they need neither Copilot credentials nor a
running Copilot runtime and make no paid requests. GitHub Actions runs the same build
on Node.js 20, 22, and 24 for pushes and pull requests.

Read the full design in [DESIGN.md](DESIGN.md), and the Fastify plugin's design in [DESIGN-FASTIFY.md](DESIGN-FASTIFY.md).
