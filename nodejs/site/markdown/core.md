---
title: Agent Core without a framework
---

# Agent Core without a framework

`@copilot-sdk-integrations/agent-core` (Agent Core) is the framework-agnostic agent layer on top
of the Copilot SDK. It works from Express, Hono, a queue worker, or a script. The NestJS, Fastify,
and AI SDK packages are built on it.
<!-- only:nestjs -->
NestJS apps use the [`CopilotModule`](nestjs.md) instead of wiring Agent Core by hand.
<!-- /only -->
<!-- only:fastify -->
Fastify apps use the [Fastify plugin](fastify.md) instead of wiring Agent Core by hand.
<!-- /only -->

**In this chapter**

- [Install](#install)
- [Create the client](#create-the-client)
- [Define and run an agent](#define-and-run-an-agent)
- [Stream a turn](#stream-a-turn)
- [Lifecycle](#lifecycle)
- [Use it from Express](#use-it-from-express)
- [Exported API](#exported-api)

## Install

```sh
npm install @copilot-sdk-integrations/agent-core zod
```

## Create the client

`createCopilot()` returns a `Copilot`: the owner of one SDK client and its runtime process.
Create one per application and share it.

```ts
import { createCopilot } from "@copilot-sdk-integrations/agent-core";

const copilot = createCopilot({
  limits: { maxConcurrentTurns: 8 },
  responseTimeout: 120_000,
});
```

The runtime starts on the first turn. All options are listed under
[Configuration](configuration.md#copilotoptions).

## Define and run an agent

`defineAgent()` validates a definition and freezes it. `copilot.agent(definition)` returns an
`AgentHandle` bound to the client.

```ts
import { z } from "zod";
import { defineAgent, permissions } from "@copilot-sdk-integrations/agent-core";

const lookupOrder = {
  name: "lookupOrder",
  description: "Look up an order by id",
  input: z.object({ id: z.string() }),
  execute: async ({ id }: { id: string }) => orders.find(id),
};

const support = defineAgent({
  name: "support",
  instructions: "You are a support agent for an online store.",
  tools: [lookupOrder],
  permissions: permissions.allowCustomTools(),
});

const agent = copilot.agent(support);

// One-shot (ephemeral session, deleted after the turn)
const text = await agent.send({ prompt: "Where is order 42?" });

// Multi-turn: the runtime keeps the history for this conversation id
await agent.send({ prompt: "Where is order 42?", conversationId: "c1" });
await agent.send({ prompt: "And when will it arrive?", conversationId: "c1" });

// Structured output, validated with Zod
const Order = z.object({ id: z.string(), status: z.enum(["shipped", "pending"]) });
const order = await agent.send({ prompt: "Extract the order from: ...", schema: Order });
```

`send` options:

| Option | Purpose |
| --- | --- |
| `prompt` | The user message. Required, non-blank. |
| `conversationId` | Persistent conversation key. Omit for an ephemeral session. |
| `schema` | A Zod schema (any object with `parse`); `send` resolves with the parsed value. |
| `signal` | `AbortSignal`; aborting cancels the turn. |
| `timeout` | Overrides `responseTimeout` for this turn, in ms. Includes queueing time. |
| `tools` | Extra tools for this call only. |
| `context` | Merged into the [`CopilotContext`](context-and-identity.md) of this turn. |
| `onPermissionRequest` | Answers `ask-user` permissions in non-streaming calls. Default: deny. |

For conversations, `agent.conversation(id, { mode, principal })` returns a bound handle with
`send(prompt, options)` and `stream(prompt, options)`. See [Conversations](conversations.md).

## Stream a turn

`agent.stream()` returns a cold `AsyncIterable` of [events](streaming.md#event-model). Each
iteration runs a new turn; breaking out of the loop aborts it.

```ts
for await (const event of agent.stream({ prompt: "Summarize order 42", conversationId: "c1" })) {
  if (event.type === "text-delta") process.stdout.write(event.delta);
  if (event.type === "error") console.error(event.error);
}
```

Errors arrive as a final `error` event instead of being thrown.

## Lifecycle

| Member | Purpose |
| --- | --- |
| `copilot.state` | `not-started`, `starting`, `running`, `stopping`, `stopped`, or `failed`. |
| `copilot.start()` | Starts the runtime. A start in flight is shared; on a `failed` client it retries immediately and resets the [restart](operations.md#runtime-restarts) backoff. Failures throw `CopilotRuntimeUnavailableError`. |
| `copilot.stop({ gracePeriodMs })` | Rejects new turns with `CopilotShuttingDownError`, waits for in-flight turns (default 30 s), aborts the rest, then stops the runtime. |
| `copilot.client()` | The started SDK client, for raw SDK access. |
| `copilot.agent(definition)` | An `AgentHandle`. |
| `copilot.capacity()` | Current admission counters (active and queued turns, limits). |
| `copilot.health()` | `{ state, version, capacity }` without starting a lazy runtime. Detects a lost runtime connection (state `failed`). |

`startup: "eager"` has no effect on its own in core: nothing starts until you call `start()` (or
run a turn).
<!-- only:nestjs -->
The NestJS module calls `start()` at bootstrap for you.
<!-- /only -->
<!-- only:fastify -->
The Fastify plugin calls `start()` in `onReady` for you.
<!-- /only -->

If the runtime process dies or disconnects, the client goes to `failed` and the next turn starts
a fresh runtime, with backoff between repeated failures. See
[Runtime restarts](operations.md#runtime-restarts).

Call `start()` at boot to fail fast instead of on the first request, and `stop()` on `SIGTERM`:

```ts
await copilot.start();
process.on("SIGTERM", async () => {
  await copilot.stop({ gracePeriodMs: 30_000 });
  process.exit(0);
});
```

## Use it from Express

```ts
import express from "express";
import { CopilotIntegrationError, runWithCopilotContext } from "@copilot-sdk-integrations/agent-core";

const app = express();
app.use(express.json());

app.post("/support/:conversationId", async (req, res, next) => {
  const controller = new AbortController();
  res.on("close", () => { if (!res.writableEnded) controller.abort(); });
  try {
    const text = await runWithCopilotContext({ principal: req.user?.id }, () =>
      agent.send({ prompt: req.body.question, conversationId: req.params.conversationId, signal: controller.signal }),
    );
    res.json({ text });
  } catch (error) {
    next(error);
  }
});
```

`runWithCopilotContext` sets the principal for session-id derivation and makes it visible to
tools, policies, and hooks. For an AI SDK `useChat` front end, see
[Vercel AI SDK](ai-sdk.md#express).

## Exported API

| Area | Exports |
| --- | --- |
| Client | `createCopilot`, `Copilot`, `CopilotOptions`, `CopilotRestartOptions` |
| Agents | `defineAgent`, `CopilotAgentDefinition`, `AgentHandle`, `BoundConversation`, `SendOptions` |
| Tools | `defineCopilotTool`, `toSdkTools`, `toJsonSchema`, `CopilotToolDefinition`, `ToolInput` |
| Governance | `permissions`, `evaluatePermission`, `composeHooks`, `PermissionKinds`, `PermissionPolicy`, `PermissionDecision` |
| Tool filters | `validateToolFilters`, `buildToolFilterConfig`, `ALL_BUILT_IN_TOOLS` (see [Tool filters](tools.md#tool-filters)) |
| MCP | `resolveMcpServers` (resolves context-derived `headers`/`env`), `CopilotMCPServerConfig` |
| Conversations | `deriveSessionId`, `memoryStore`, `SessionStore` |
| Sessions | `copilot.sessions` (`CopilotSessions`, `CopilotSessionMetadata`, `CopilotSessionMessage`), `createSessionCleanup`, `sessionCleanupInterval`, `COPILOT_SESSION_ID_PREFIX` (see [Listing and cleaning up sessions](conversations.md#listing-and-cleaning-up-sessions)) |
| Context | `getCopilotContext`, `runWithCopilotContext`, `bindCopilotContext`, `CopilotContext` |
| Events | `mapSessionEvent`, `CopilotAgentEvent` |
| Errors | `CopilotIntegrationError` and subclasses (see [Operations](operations.md#errors)) |
| Testing | `@copilot-sdk-integrations/agent-core/testing` (see [Testing](testing.md)) |
