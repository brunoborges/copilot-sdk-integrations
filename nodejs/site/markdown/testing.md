---
title: Testing
---

# Testing

Agent code is tested against a scripted runtime: `CopilotTestRuntime` from
`@copilot-sdk-integrations/agent-core/testing`. It replaces the Copilot runtime process, so tests
need no CLI, no credentials, and make no paid requests, while your tools, permission policies,
hooks, tool filters, and conversation handling run for real.

**In this chapter**

- [Core tests](#core-tests)
- [Scripting replies](#scripting-replies)
- [Recordings](#recordings)
<!-- only:nestjs -->
- [NestJS tests](#nestjs-tests)
<!-- /only -->
<!-- only:fastify -->
- [Fastify tests](#fastify-tests)
<!-- /only -->
- [Testing AI SDK routes](#testing-ai-sdk-routes)

## Core tests

```ts
import { createTestCopilot } from "@copilot-sdk-integrations/agent-core/testing";
import { expect, test } from "vitest";

test("looks up the order", async () => {
  await using test = createTestCopilot({
    script: (rt) => rt.onPrompt(/order 42/).callTool("lookupOrder", { id: "42" }).thenReply("Order 42 shipped."),
  });

  const text = await test.copilot.agent(supportAgent).send({ prompt: "Where is order 42?" });

  expect(text).toBe("Order 42 shipped.");
  expect(test.runtime.toolCalls.map((c) => c.name)).toEqual(["lookupOrder"]);
});
```

`createTestCopilot(options)` accepts every [`CopilotOptions`](configuration.md#copilotoptions)
field except `clientFactory`, `cliPath`, and `cliUrl`, plus:

| Option | Description |
| --- | --- |
| `script` | `(runtime) => ...` registers scripted replies. |
| `runtime` | Reuse an existing `CopilotTestRuntime`. Default: a new one. |
| `models` | Models returned by `listModels()`. |
| `defaultModel` | Model reported in events when the agent has none. Default `"test-model"`. |

It returns `{ copilot, runtime, stop }` and is `AsyncDisposable`, so `await using` stops it.
Test-friendly defaults (`TEST_COPILOT_DEFAULTS`), each overridable: a silent logger, a
throwaway `baseDirectory`, a 10 s `responseTimeout`, a 5 s `approvalWindow` and
`conversationWaitMs`, and a 1 s shutdown grace period.

## Scripting replies

`runtime.onPrompt(matcher)` registers a rule; `matcher` is a substring, a `RegExp`, or a
`(prompt) => boolean`. Rules are tried in registration order against the prompt as the model
sees it (after `onUserPromptSubmitted` rewrites). A prompt that matches no rule fails the turn
with an error pointing at `onPrompt`. Steps run in order:

| Step | Effect |
| --- | --- |
| `reply(text)` / `thenReply(text)` | Reply with a complete message. |
| `replyWith((turn) => text)` | Compute the reply from the prompt, tool calls, and `turn.toolResult(name)`. |
| `streamReply(chunks)` | Stream the reply as deltas. |
| `replyJson(value)` | Reply with JSON, for [structured output](agents.md#structured-output). |
| `reasoning(text)` | Emit reasoning. |
| `callTool(name, args?)` | Call a tool through the real permission policy, hooks, and filters. |
| `fail(message, errorType?)` | Fail the turn with a session error (default type `model_error`). |
| `delay(ms)` | Wait, for timeout and cancellation tests. |
| `usage({ inputTokens, outputTokens, model, cost })` | Emit a usage event. |
| `emit(event \| (turn) => event)` | Emit any SDK session event. |
| `times(n)` / `once()` | Limit how often the rule matches (default: unlimited). |

Call `onPrompt(...)` on the builder to chain the next rule:

```ts
runtime
  .onPrompt("refund").callTool("refund", { orderId: "42" }).thenReply("Refunded.")
  .onPrompt(/.*/).reply("I can only help with orders.");
```

`runtime.failStart(error, times?)` makes the runtime fail to start, `runtime.crash()` simulates the
runtime process dying (in-flight turns end, the client disconnects; see
[runtime restarts](operations.md#runtime-restarts)), `runtime.setModels(models)`
changes the model list, `runtime.setModifiedTime(sessionId, date)` backdates a session (to test the
[cleanup job](conversations.md#cleanup-job)), and `runtime.reset()` clears rules and recordings
between tests.

## Recordings

| Property | Content |
| --- | --- |
| `prompts` | Prompt strings, in order. |
| `receivedPrompts` | `{ sessionId, messageId, prompt }` per prompt. |
| `toolCalls` | `{ sessionId, toolCallId, name, args, result?, error?, approved }` per call. |
| `permissionRequests` | `{ sessionId, request, result }` per permission request. |
| `createdSessions` / `resumedSessions` / `deletedSessions` | Session configs and ids. |
| `sessionIds`, `sessionConfig(id)`, `events(id)` | Per-session state and emitted events. |
| `clientOptions` | Options the client was created with (tokens, `cliUrl`, ...). |
| `abortCount` | Number of `session.abort()` calls. |
| `started` / `stopped` | Runtime lifecycle. |
| `crashCount` | Number of `crash()` calls. |

Assert on governance, not just text: for example, that a denied tool never ran
(`toolCalls[0].approved === false`) or that the session config hid built-in tools.

<!-- only:nestjs -->
## NestJS tests

`CopilotTestingModule` from `@copilot-sdk-integrations/nestjs/testing` registers
`CopilotModule` globally with the scripted runtime. Inject `CopilotTestRuntime` to script
replies and read recordings:

```ts
import { Test } from "@nestjs/testing";
import { CopilotModule } from "@copilot-sdk-integrations/nestjs";
import { CopilotTestingModule, CopilotTestRuntime } from "@copilot-sdk-integrations/nestjs/testing";

const moduleRef = await Test.createTestingModule({
  imports: [
    CopilotTestingModule.forRoot((runtime) =>
      runtime.onPrompt(/order 42/).callTool("lookupOrder", { id: "42" }).thenReply("Order 42 shipped.")),
    CopilotModule.forFeature([SupportAgent]),
  ],
  providers: [OrdersTools, { provide: OrdersService, useValue: fakeOrders }],
}).compile();
await moduleRef.init();

await expect(moduleRef.get(SupportAgent).answer("c1", "Where is order 42?")).resolves.toBe("Order 42 shipped.");
expect(moduleRef.get(CopilotTestRuntime).prompts).toEqual(["Where is order 42?"]);
await moduleRef.close();
```

- `forRoot(script?, options?)` takes the module options (except `copilotFactory`, `cliPath`,
  `cliUrl`, and `clientFactory`) plus `models` and `defaultModel`.
- `forRootAsync({ script, useFactory, inject, imports })` builds the options from other providers.
- Defaults: a silent logger and a 1 s shutdown grace period.
- Test runners must emit decorator metadata (`emitDecoratorMetadata`) for constructor injection
  by type. With Vitest, use [`unplugin-swc`](https://github.com/unplugin/unplugin-swc).

<!-- /only -->
<!-- only:fastify -->
## Fastify tests

`buildCopilotTestApp(build, script)` from `@copilot-sdk-integrations/fastify/testing` passes a
scripted client to your app factory and stops it when the app closes. Call routes with
`app.inject()` and split SSE bodies with `parseSse()`:

```ts
import { buildCopilotTestApp, parseSse } from "@copilot-sdk-integrations/fastify/testing";

const { app, runtime } = await buildCopilotTestApp((options) => buildApp(options), (rt) =>
  rt.onPrompt(/order 42/).callTool("lookupOrder", { id: "42" }).thenReply("Order 42 shipped."));
const res = await app.inject({ method: "POST", url: "/ask", payload: { question: "Where is order 42?" } });
expect(res.json()).toEqual({ answer: "Order 42 shipped." });
await app.close();
```

See [Fastify plugin](fastify.md#testing).
<!-- /only -->

## Testing AI SDK routes

The AI SDK adapters work on any `AgentHandle`, so point them at a test copilot and read the
chunks:

```ts
await using test = createTestCopilot({ script: (rt) => rt.onPrompt("Hi").streamReply(["Hel", "lo"]) });
const stream = streamCopilotChat({
  agent: test.copilot.agent(support),
  messages: [{ id: "u1", role: "user", parts: [{ type: "text", text: "Hi" }] }],
});
const chunks = [];
for await (const chunk of stream) chunks.push(chunk);
```
