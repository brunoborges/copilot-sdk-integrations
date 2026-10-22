---
title: Examples
---

# Examples

Two runnable apps live in
[`nodejs/examples`](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/nodejs/examples).
Both host a "Node.js advisor" agent, the Node.js counterpart of the Spring Boot
`spring-advisor` sample, and both have tests that run without credentials or a runtime
process.

## NestJS chat

[`examples/nestjs-chat`](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/nodejs/examples/nestjs-chat)
is a NestJS app with a minimal React UI built on the AI SDK's `useChat`, including buttons to
approve tools. It keeps the default `nest new` setup (CommonJS with decorator metadata) to show
that CommonJS Nest apps can use the ESM packages.

| Feature | Docs |
| --- | --- |
| `CopilotModule.forRootAsync` with `ConfigService`: token, BYOK, model, lazy startup, limits | [Configuration](configuration.md#async-configuration) |
| A declarative agent with string, structured-output (`@Returns`), and `Observable` methods | [Agents](agents.md#nestjs-agent-classes) |
| `@CopilotTool` methods reading the caller from `invocation.context` | [Tools](tools.md) |
| An injectable permission policy: approve reads, ask before writes, deny everything else | [Governance](governance.md#permission-policies) |
| Per-user identity: a guard sets `request.user`, which becomes the principal | [Context and identity](context-and-identity.md) |
| Multi-turn conversations and SSE streaming with `toSseEvents` | [Streaming](streaming.md) |
| A `useChat` endpoint with tool approvals | [Vercel AI SDK](ai-sdk.md#tool-approvals) |
| Terminus health check and HTTP error mapping | [Operations](operations.md) |
| End-to-end tests with `CopilotTestingModule` | [Testing](testing.md#nestjs-tests) |

## Express

[`examples/express`](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/nodejs/examples/express)
is a plain Express 5 app that uses only `@copilot-sdk-integrations/core` and
`@copilot-sdk-integrations/ai`.

| Feature | Docs |
| --- | --- |
| `createCopilot` with lazy start and graceful shutdown | [Core](core.md) |
| `defineAgent` with instructions, tools, and a permission policy | [Agents](agents.md) |
| A core tool (`defineCopilotTool` with Zod) and an AI SDK `tool()` with `needsApproval` | [Tools](tools.md) |
| Per-user identity passed as turn context and read by tools | [Context and identity](context-and-identity.md) |
| A `useChat` endpoint, a one-shot SSE stream, and a request/response endpoint | [Vercel AI SDK](ai-sdk.md#express-and-nestjs) |
| Health without starting the runtime | [Operations](operations.md#health) |
| Tests with `createTestCopilot` | [Testing](testing.md#core-tests) |

## Running an example

The examples are npm workspaces of the `nodejs` folder. Each example's README lists
its prerequisites, environment variables, and endpoints:

```sh
cd nodejs
npm install
npm run build                          # packages and examples
npm start -w examples/express          # or: npm start -w examples/nestjs-chat
npm run dev -w examples/nestjs-chat/web  # the chat UI, proxying /api to the NestJS app
npm test -w examples/express
```

Without a token, the examples use the user logged in to the Copilot CLI. Their tests use the
scripted runtime and need neither.
