# Copilot for Node.js

Status: **design**. Nothing is implemented or published yet.

Integrations for the
[Copilot SDK for Node.js/TypeScript](https://github.com/github/copilot-sdk/tree/main/nodejs):

- **core**: a framework-agnostic layer for client lifecycle, typed agents, tools,
  governance (deny-by-default permissions), conversations, and a scripted test runtime.
  Usable from Express, Fastify, Hono, or plain Node.js.
- **nestjs**: a `CopilotModule` modeled on the [Spring Boot starter](../spring-boot/),
  with `@CopilotAgent` abstract classes, `@CopilotTool` provider methods, Terminus
  health, and a testing module.
- **ai**: an [AI SDK](https://ai-sdk.dev) adapter, so `useChat` front ends work against
  a Copilot backend.

```ts
@CopilotAgent({ name: "support", instructions: "Help customers with orders." })
export abstract class SupportAgent {
  @UserMessage()
  abstract answer(@ConversationId() id: string, @Message() question: string): Promise<string>;
}
```

Read the full design in [DESIGN.md](DESIGN.md).
