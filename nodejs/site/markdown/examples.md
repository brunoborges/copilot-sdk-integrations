---
title: Examples
---

# Examples

Runnable apps live in
[`nodejs/examples`](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/nodejs/examples).
Each hosts a "Node.js advisor" agent, the Node.js counterpart of the Spring Boot
`spring-advisor` sample, and has tests that run without credentials or a runtime process.

<!-- only:nestjs -->
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
<!-- /only -->
<!-- only:fastify -->

## Fastify

[`examples/fastify`](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/nodejs/examples/fastify)
is a Fastify 5 app built on the [Fastify plugin](fastify.md), with the same advisor and
endpoints as the Express example plus a raw-events SSE route.

| Feature | Docs |
| --- | --- |
| A `buildApp(opts)` factory registering `copilotPlugin` (lazy start) and `copilotAiPlugin`; `app.close()` on signals | [Fastify plugin](fastify.md#register-the-plugin) |
| Typed agent names with `CopilotAgentRegistry` | [Fastify plugin](fastify.md#agents-and-typing) |
| Per-user identity from the plugin's context hook, read by tools | [Fastify plugin](fastify.md#request-context) |
| Route schemas for validation, and Copilot errors mapped to HTTP | [Fastify plugin](fastify.md#errors) |
| `reply.copilotEvents` (raw SSE) and `reply.copilotUIStream` (UI message stream) | [Fastify plugin](fastify.md#streaming) |
| A `useChat` endpoint with owner-scoped tool approvals (`reply.copilotChat`) | [Fastify plugin](fastify.md#usechat-and-approvals) |
| The plugin's health route | [Fastify plugin](fastify.md#health-info-and-under-pressure) |
| Tests with `buildCopilotTestApp` and `app.inject()` | [Fastify plugin](fastify.md#testing) |
<!-- /only -->

## Express

The Express example shows Agent Core without a framework integration.
[`examples/express`](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/nodejs/examples/express)
is a plain Express 5 app that uses only `@copilot-sdk-integrations/agent-core` and
`@copilot-sdk-integrations/ai`.

| Feature | Docs |
| --- | --- |
| `createCopilot` with lazy start and graceful shutdown | [Agent Core](core.md) |
| `defineAgent` with instructions, tools, and a permission policy | [Agents](agents.md) |
| A core tool (`defineCopilotTool` with Zod) and an AI SDK `tool()` with `needsApproval` | [Tools](tools.md) |
| Per-user identity passed as turn context and read by tools | [Context and identity](context-and-identity.md) |
| A `useChat` endpoint, a one-shot SSE stream, and a request/response endpoint | [Vercel AI SDK](ai-sdk.md#express) |
| Health without starting the runtime | [Operations](operations.md#health) |
| Tests with `createTestCopilot` | [Testing](testing.md#core-tests) |

## Running an example

The examples are npm workspaces of the `nodejs` folder. Each example's README lists
its prerequisites, environment variables, and endpoints:

```sh
cd nodejs
npm install
npm run build                          # packages and examples
<!-- only:nestjs -->
npm start -w examples/nestjs-chat      # or: examples/express
npm run dev -w examples/nestjs-chat/web  # the chat UI, proxying /api to the NestJS app
npm test -w examples/nestjs-chat       # or: examples/express
<!-- /only -->
<!-- only:fastify -->
npm start -w examples/fastify          # or: examples/express
npm test -w examples/fastify           # or: examples/express
<!-- /only -->
```

Without a token, the examples use the user logged in to the Copilot CLI. Their tests use the
scripted runtime and need neither.
