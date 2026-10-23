# @copilot-sdk-integrations/agent-core

Agent Core: the framework-agnostic integration layer for the [GitHub Copilot SDK](https://github.com/github/copilot-sdk) for Node.js. It manages one runtime client per application (lazy start, admission limits, timeouts, graceful shutdown, restart after a crash), declarative agents with Zod-typed tools, permission policies, conversations, streaming events, and a scripted test runtime. [`@copilot-sdk-integrations/nestjs`](../nestjs), [`@copilot-sdk-integrations/fastify`](../fastify), and [`@copilot-sdk-integrations/ai`](../ai) are built on it; see [`DESIGN.md`](../../DESIGN.md) for the full design.

```sh
npm install @copilot-sdk-integrations/agent-core zod
```

The package is ESM-only. CommonJS apps load it with `require()` on Node `^20.19` or `>=22.12` (`require(esm)`).

## Create the client

Create one `Copilot` per application and share it. The runtime starts on the first turn.

```ts
import { createCopilot } from "@copilot-sdk-integrations/agent-core";

const copilot = createCopilot({
  limits: { maxConcurrentTurns: 8, maxQueuedTurns: 32 },
  responseTimeout: 120_000,
  restart: { maxAttempts: 5, backoffMs: 1_000 }, // recovery when the runtime process dies
});

await copilot.start(); // optional: fail fast at boot (`startup: "eager"` alone does not start it in core)
process.on("SIGTERM", () => copilot.stop({ gracePeriodMs: 30_000 }));
```

`copilot.health()` returns `{ state, version, capacity }` without starting a lazy runtime; `state` is `failed` after a failed start or a lost runtime connection.

## Define an agent

```ts
import { z } from "zod";
import { defineAgent, defineCopilotTool, permissions } from "@copilot-sdk-integrations/agent-core";

const lookupOrder = defineCopilotTool({
  name: "lookupOrder",
  description: "Look up an order by id",
  input: z.object({ id: z.string() }),
  execute: async ({ id }) => orders.find(id),
});

const support = defineAgent({
  name: "support",
  instructions: "You are a support agent for an online store.",
  tools: [lookupOrder],
  permissions: permissions.allowCustomTools(), // deny-by-default otherwise
});

const agent = copilot.agent(support);
```

Tools can also be SDK `defineTool()` tools or AI SDK `tool()` records. Built-in runtime tools are hidden unless the agent sets `builtInTools: true`; narrow further with `availableTools`/`excludedTools`.

## Send, stream, structured output

```ts
// One-shot turn in an ephemeral session
const text = await agent.send({ prompt: "Where is order 42?" });

// Multi-turn: the runtime keeps the history for the conversation id
await agent.send({ prompt: "Where is order 42?", conversationId: "c1" });
const chat = agent.conversation("c1", { mode: "create-or-resume" });
await chat.send("And when will it arrive?");

// Structured output, validated with Zod
const Order = z.object({ id: z.string(), status: z.enum(["shipped", "pending"]) });
const order = await agent.send({ prompt: "Extract the order from: ...", schema: Order });

// Streaming: breaking out of the loop aborts the turn; errors arrive as an `error` event
for await (const event of agent.stream({ prompt: "Summarize order 42", conversationId: "c1" })) {
  if (event.type === "text-delta") process.stdout.write(event.delta);
}
```

Every call accepts `signal`, `timeout`, extra `tools`, and `context`. Wrap request handling in `runWithCopilotContext({ principal })` so conversations are scoped per user and tools, policies, and hooks can read `getCopilotContext()`.

## Sessions

`copilot.sessions` lists, reads, and deletes persisted sessions without resuming them: `list()`, `get(id)`, `exists(id)`, `getMessages(id)`, `delete(id)`, and `sessionId(agent, conversationId)`. `createSessionCleanup(copilot.sessions, { maxAge })` periodically deletes this library's sessions (ids starting with `copilot-`) that haven't been modified for `maxAge` ms; it skips remote sessions and sessions with a turn running, and never starts the runtime. See [Conversations](../../site/markdown/conversations.md#listing-and-cleaning-up-sessions).

## Permissions

Policies decide each runtime permission request (`approve`, `deny`, `ask-user`, or `abstain`):

```ts
import { PermissionKinds, permissions } from "@copilot-sdk-integrations/agent-core";

permissions.denyAll();                          // the default
permissions.allowReadOnly();
permissions.allowTools(["lookupOrder"]);
permissions.allowMcpServers(["github"]);
permissions.allowKinds([PermissionKinds.READ, PermissionKinds.URL]);
permissions.anyOf(permissions.allowReadOnly(), permissions.askUser());
permissions.allOf(permissions.allowCustomTools(), (request, { context }) => (context.principal ? "approve" : "deny"));
```

`ask-user` decisions surface as `permission-request` events when streaming, or go to `onPermissionRequest` in `send()`.

## Fresh-session loops (Ralph loop)

`runCopilotLoop` sends the same prompt repeatedly, each iteration in a new ephemeral session (a `send()` without a `conversationId`). State lives on disk (for example `IMPLEMENTATION_PLAN.md` in the agent's `workingDirectory`), and builds or tests are the quality gate.

```ts
import { readFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { runCopilotLoop } from "@copilot-sdk-integrations/agent-core";

const result = await runCopilotLoop(copilot.agent(builder), {
  prompt: () => readFile("PROMPT_build.md", "utf8"), // re-read every iteration
  maxIterations: 20,
  iterationTimeout: 15 * 60_000,
  check: () => spawnSync("npm", ["test"], { stdio: "inherit" }).status === 0,
  stopWhen: (it) => it.output?.includes("ALL TASKS DONE") ?? false,
  onIteration: (it) => console.log(`iteration ${it.number}: ${it.durationMs} ms`),
  signal: AbortSignal.timeout(4 * 60 * 60_000),
});
console.log(result.stopReason); // "stop-condition" | "check" | "max-iterations" | "failed" | "cancelled"
```

The loop stops at the first of `stopWhen` (after a successful iteration), `check` returning `false` (only between iterations), a failed or timed-out iteration (unless `continueOnFailure`), `signal` aborting, or `maxIterations`. Pass a function `({ iteration, prompt, signal, timeout }) => output` instead of an agent to run a custom step; it must open a fresh session itself.

Each iteration uses the agent's permission policy; nothing is approved implicitly. Run loops against a disposable checkout inside a container or VM, observe tool use with the agent's hooks, and schedule them with any job runner (BullMQ, node-cron, a CI job).

## Error recovery

`errorRecoveryHooks()` is an opt-in set of `SessionHooks` based on the Copilot SDK cookbook's
[error-recovery recipe](https://github.com/github/awesome-copilot/blob/main/cookbook/copilot-sdk/python/error-recovery-hooks.md).
Pass it as global `hooks` (or an agent's `hooks`); it composes with other hooks.

```ts
import { createCopilot, errorRecoveryHooks } from "@copilot-sdk-integrations/agent-core";

const copilot = createCopilot({
  hooks: errorRecoveryHooks({ maxConsecutiveFailures: 3, retryCount: 2, onFailure: (e) => log.debug(e) }),
});
```

- `onPostToolUse` / `onPostToolUseFailure` classify the tool output as permission denied, shell error, or
  generic tool failure by case-insensitive phrase lists (`permissionPhrases`, `shellErrorPhrases`) and add a
  `[SYSTEM NOTE: ...]` as `additionalContext` telling the model not to stop. The tool result is unchanged.
- After `maxConsecutiveFailures` (default 3, `0` = unlimited) failures of the same tool in a session the note
  stops, so the model is not nudged into a loop. A successful call resets the count.
- `onErrorOccurred` retries transient errors (timeouts, 429, 5xx, connection resets, or errors the SDK marks
  `recoverable`) `retryCount` times. Other errors get `unrecoverableHandling` (`"skip"` by default, `"abort"`,
  or `null` for the SDK default).

In NestJS pass it as `CopilotModule.forRoot({ hooks: errorRecoveryHooks() })`; in Fastify as
`client: { hooks: errorRecoveryHooks() }`.

## Testing

`@copilot-sdk-integrations/agent-core/testing` replaces the runtime with a scripted, in-process one: no CLI, no network.

```ts
import { createTestCopilot } from "@copilot-sdk-integrations/agent-core/testing";

await using test = createTestCopilot({
  script: (rt) => rt.onPrompt(/order/).callTool("lookupOrder", { id: "42" }).thenReply("It shipped."),
});
const text = await test.copilot.agent(support).send({ prompt: "Where is order 42?" });

expect(text).toBe("It shipped.");
expect(test.runtime.toolCalls[0]).toMatchObject({ name: "lookupOrder", approved: true });
```

`runtime.failStart()` and `runtime.crash()` simulate start failures and a runtime that dies mid-flight.

## Documentation

See the [documentation site](../../site/markdown/core.md) for configuration, conversations, streaming, governance, operations, and testing.
