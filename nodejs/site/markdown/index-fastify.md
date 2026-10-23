---
title: Documentation
page: index
sites: fastify
---

# Copilot Agents for Fastify

`@copilot-sdk-integrations/fastify` is a Fastify 5 plugin for the
[GitHub Copilot SDK for Node.js/TypeScript](https://github.com/github/copilot-sdk/tree/main/nodejs).
Register it once, declare agents with `defineAgent`, and call them from routes through
`request.copilot`. Underneath, Agent Core (`@copilot-sdk-integrations/agent-core`) adds what the
SDK leaves to the application: a managed client lifecycle with admission limits, typed agents,
tools with deny-by-default permissions, server-side conversations, request context, streaming,
and a scripted test runtime that needs no credentials.

> **Status:** in development. The packages are versioned `0.1.0` and have not been published
> to npm. Build them from the [repository](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/nodejs).

```ts
const support = defineAgent({
  name: "support",
  instructions: "Help customers with orders.",
  tools: [lookupOrder],
  permissions: permissions.allowCustomTools(),
});

await app.register(copilotPlugin, { agents: [support] });

app.post<{ Body: { question: string } }>("/ask", async (request) => ({
  answer: await request.copilot.agent("support").send({ prompt: request.body.question }),
}));
```

Building on NestJS instead? See [Copilot Agents for NestJS](https://brunoborges.github.io/copilot-sdk-integrations/nestjs/).

## Baseline

| Component | Version |
| --- | --- |
| Node.js | `^20.19.0` or `>=22.12.0` (ESM-only packages) |
| Copilot SDK (`@github/copilot-sdk`) | `^1.0.26` |
| Zod | `^4` |
| Fastify | `^5` |
| Vercel AI SDK (`ai`), optional | `^7` |

## Packages

| Package | Use it when | Depends on |
| --- | --- | --- |
| `@copilot-sdk-integrations/fastify` | You build a Fastify 5 app and want `fastify.copilot` and request-bound agents, request context, HTTP error mapping, SSE, `useChat` routes with approvals, health and info routes, and `app.inject()` testing. | Agent Core, Fastify (`ai` for `useChat`) |
| `@copilot-sdk-integrations/agent-core` | Always installed with the plugin. Used directly for agent definitions, tools, and permission policies, and for [apps without a framework](core.md) such as Express, Hono, or workers. | `@github/copilot-sdk`, `zod` |
| `@copilot-sdk-integrations/ai` | Your front end uses the AI SDK (`useChat`), or you want `generateText`/`streamText` against Copilot agents. | Agent Core, `ai` |

The Fastify plugin is a thin layer over Agent Core. Every concept on the core pages (agents,
tools, permissions, conversations, events) applies unchanged in Fastify.

## Read by chapter

| Chapter | Covers |
| --- | --- |
| [Fastify plugin](fastify.md) | Installation, `copilotPlugin`, typed agents, request context, errors, SSE, `useChat` approvals, health, lifecycle, and `buildCopilotTestApp`. |
| [Agent Core without a framework](core.md) | `createCopilot`, `defineAgent`, `send`, `stream`, lifecycle, and Express usage. |
| [Agents](agents.md) | Agent definitions, instructions, models, BYOK providers, MCP servers, and structured output. |
| [Tools](tools.md) | Tool definitions, Zod and JSON Schema input, AI SDK tools, and tool filters. |
| [Tool governance and hooks](governance.md) | Permission policies, `ask-user` approvals, hooks, and their composition rules. |
| [Conversations](conversations.md) | Session ids, conversation modes, locking, admission, timeouts, and session stores. |
| [Context and per-user identity](context-and-identity.md) | `CopilotContext`, the plugin's context hook, and per-user GitHub tokens. |
| [Streaming](streaming.md) | The event model, async iterables, and Server-Sent Events. |
| [Vercel AI SDK](ai-sdk.md) | UI message streams, `useChat` with tool approvals, and the AI SDK provider. |
| [Configuration](configuration.md) | Every `CopilotOptions` option with defaults. |
| [Testing](testing.md) | The scripted `CopilotTestRuntime`, `createTestCopilot`, and `buildCopilotTestApp`. |
| [Operations](operations.md) | Limits, health, the info endpoint, graceful shutdown, error mapping, observability, and security. |
| [Example applications](examples.md) | The Fastify and Express examples. |

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

The Fastify and NestJS sites are generated from the same Markdown in `nodejs/site/markdown` by
a small Node.js script.

```sh
cd nodejs
npm ci
npm run site
open site/dist/fastify/index.html
```

The build fails on broken internal links or anchors. GitHub Pages publishes the output under
`/fastify/` and `/nestjs/`, together with the other integration sites.
