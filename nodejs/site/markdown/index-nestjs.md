---
title: Documentation
page: index
sites: nestjs
---

# Copilot Agents for NestJS

`@copilot-sdk-integrations/nestjs` brings the
[GitHub Copilot SDK for Node.js/TypeScript](https://github.com/github/copilot-sdk/tree/main/nodejs)
to NestJS applications. Declare agents as abstract classes, expose provider methods as tools,
and inject agents into controllers. Underneath, Agent Core (`@copilot-sdk-integrations/agent-core`)
adds what the SDK leaves to the application: a managed client lifecycle with admission limits,
typed agents, tools with deny-by-default permissions, server-side conversations, request
context, streaming, and a scripted test runtime that needs no credentials.

> **Status:** in development. The packages are versioned `0.1.0` and have not been published
> to npm. Build them from the [repository](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/nodejs).

```ts
@CopilotAgent({ name: "support", instructions: "Help customers with orders.", tools: [OrdersTools] })
export abstract class SupportAgent {
  @UserMessage()
  answer(@ConversationId() id: string, @Message() question: string): Promise<string> {
    return implementedByCopilot();
  }
}
```

Building on Fastify instead? See [Copilot Agents for Fastify](https://brunoborges.github.io/copilot-sdk-integrations/fastify/).

## Baseline

| Component | Version |
| --- | --- |
| Node.js | `^20.19.0` or `>=22.12.0` (ESM packages; CommonJS apps load them with `require(esm)`) |
| Copilot SDK (`@github/copilot-sdk`) | `^1.0.26` |
| Zod | `^4` |
| NestJS (`@nestjs/common`, `@nestjs/core`) | `^11` or `^12` |
| Vercel AI SDK (`ai`), optional | `^7` |

## Packages

| Package | Use it when | Depends on |
| --- | --- | --- |
| `@copilot-sdk-integrations/nestjs` | You build a NestJS application and want `CopilotModule`, `@CopilotAgent` classes, `@CopilotTool` providers, request context, HTTP error mapping, SSE, health and info endpoints, and a testing module. | Agent Core, NestJS |
| `@copilot-sdk-integrations/agent-core` | Always installed with the module. Used directly for agent definitions, permission policies, and [apps without a framework](core.md) such as Express, Hono, or workers. | `@github/copilot-sdk`, `zod` |
| `@copilot-sdk-integrations/ai` | Your front end uses the AI SDK (`useChat`), or you want `generateText`/`streamText` against Copilot agents. | Agent Core, `ai` |

The NestJS module is a thin layer over Agent Core. Every concept on the core pages (agents,
tools, permissions, conversations, events) applies unchanged in NestJS.

## Read by chapter

| Chapter | Covers |
| --- | --- |
| [Getting started](getting-started.md) | Installation, `CopilotModule`, a first agent, tool, and controller. |
| [Agent Core without a framework](core.md) | `createCopilot`, `defineAgent`, `send`, `stream`, lifecycle, and Express usage. |
| [Agents](agents.md) | Agent definitions, instructions, models, BYOK providers, MCP servers, NestJS decorators, and structured output. |
| [Tools](tools.md) | Tool definitions, Zod and JSON Schema input, AI SDK tools, `@CopilotTool` providers, interceptors, and tool filters. |
| [Tool governance and hooks](governance.md) | Permission policies, `ask-user` approvals, hooks, and their composition rules. |
| [Conversations](conversations.md) | Session ids, conversation modes, locking, admission, timeouts, and session stores. |
| [Context and per-user identity](context-and-identity.md) | `CopilotContext`, the NestJS interceptor, `nestjs-cls`, and per-user GitHub tokens. |
| [Streaming](streaming.md) | The event model, async iterables, Observables, and Server-Sent Events. |
| [Vercel AI SDK](ai-sdk.md) | UI message streams, `useChat` with tool approvals, and the AI SDK provider. |
| [Configuration](configuration.md) | Every `CopilotOptions` and `CopilotModule` option with defaults. |
| [Testing](testing.md) | The scripted `CopilotTestRuntime`, `createTestCopilot`, and `CopilotTestingModule`. |
| [Operations](operations.md) | Limits, health, the info endpoint, graceful shutdown, error mapping, observability, and security. |
| [Example applications](examples.md) | The NestJS chat and Express examples. |

## Design principles

- **Secure by default.** An agent with no permission policy denies every tool call, and
  built-in tools (shell, file writes, URL fetches) are hidden unless enabled.
- **Server-side conversations.** The Copilot runtime keeps history in its session. A
  conversation id plus the caller's principal derive a stable, per-user session id.
- **Bounded concurrency.** Turns are admitted through a global slot limit, a queue, and a
  per-conversation lock, and they always end with a timeout.
- **No hidden I/O in tests.** The scripted runtime replaces the Copilot process, so tests are
  fast, deterministic, and free.

## Build and preview this site

The NestJS and Fastify sites are generated from the same Markdown in `nodejs/site/markdown` by
a small Node.js script.

```sh
cd nodejs
npm ci
npm run site
open site/dist/nestjs/index.html
```

The build fails on broken internal links or anchors. GitHub Pages publishes the output under
`/nestjs/` and `/fastify/`, together with the other integration sites.
