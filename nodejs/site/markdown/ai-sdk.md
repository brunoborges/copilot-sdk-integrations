---
title: Vercel AI SDK
---

# Vercel AI SDK

`@copilot-sdk-integrations/ai` connects Copilot agents to the [Vercel AI SDK](https://ai-sdk.dev)
v7 (`ai@^7`):

- **`useChat` handler.** `streamCopilotChat` turns a `useChat` request into a Copilot turn and
  returns a UI message stream, including the tool-approval round trip.
- **UI message streams.** `toUIMessageStream` maps any [Copilot event stream](streaming.md) to
  the AI SDK UI message stream protocol.
- **Provider.** `createCopilotProvider` returns a `LanguageModelV4`, so `generateText` and
  `streamText` can run on Copilot agents. `createCopilotAgent` returns an AI SDK `Agent`.

```sh
npm install @copilot-sdk-integrations/ai @copilot-sdk-integrations/agent-core ai
```

**In this chapter**

- [Chat UIs with useChat](#chat-uis-with-usechat)
- [Express](#express)
<!-- only:nestjs -->
- [NestJS](#nestjs)
<!-- /only -->
<!-- only:fastify -->
- [Fastify](#fastify)
<!-- /only -->
- [Tool approvals](#tool-approvals)
- [UI message stream mapping](#ui-message-stream-mapping)
- [AI SDK provider](#ai-sdk-provider)
- [AI SDK agents](#ai-sdk-agents)

## Chat UIs with useChat

A Next.js route handler:

```ts
// app/api/chat/route.ts
import { createCopilot, defineAgent, permissions } from "@copilot-sdk-integrations/agent-core";
import { createCopilotChatResponse } from "@copilot-sdk-integrations/ai";
import type { UIMessage } from "ai";

const copilot = createCopilot();
const support = defineAgent({ name: "support", tools: [{ lookupOrder }], permissions: permissions.askUser() });

export async function POST(req: Request) {
  const { id, messages }: { id: string; messages: UIMessage[] } = await req.json();
  return createCopilotChatResponse({
    agent: copilot.agent(support),
    messages,
    conversationId: id, // Copilot keeps the history; only the latest user message is sent
    signal: req.signal,
  });
}
```

Copilot keeps the history server-side, keyed by `conversationId` (usually the `useChat` chat
`id`). `fromUIMessages(messages, { conversationId })` reads the request: only the text parts of
the latest user message become the prompt; earlier messages, files, and data parts are ignored.
When the last message is an assistant message, its answered tool approvals are read instead.
Omit `conversationId` for an ephemeral turn.

`streamCopilotChat(options)` returns the `ReadableStream<UIMessageChunk>`;
`createCopilotChatResponse(options, init?)` wraps it in a `Response`. The options are:

| Option | Description |
| --- | --- |
| `agent` | An `AgentHandle` (`copilot.agent(definition)`). |
| `messages` | The `messages` of the `useChat` request body. |
| `conversationId` | Conversation key. Omit for an ephemeral turn. |
| `approvals` | Where suspended turns wait for approvals. Default `defaultApprovalStore`. |
| `signal` | Aborts the turn while it streams (a suspended turn is kept until answered or expired). |
| `tools`, `context`, `timeout` | As for `agent.send()`. |
| `sendReasoning`, `sendStart`, `sendFinish`, `dynamicTools`, `messageMetadata`, `onError`, `generateId` | Stream options; see [the mapping](#ui-message-stream-mapping). |

## Express

`pipeCopilotChatToResponse(res, options)` writes the stream to a Node.js `ServerResponse`:

```ts
import { pipeCopilotChatToResponse } from "@copilot-sdk-integrations/ai";

app.post("/api/chat", express.json(), async (req, res) => {
  await pipeCopilotChatToResponse(res, { agent: copilot.agent(support), messages: req.body.messages, conversationId: req.body.id });
});
```

Cancelling the stream, for example when the browser disconnects, aborts the Copilot turn.

<!-- only:nestjs -->
## NestJS

`pipeCopilotChatToResponse` works with the Express response that NestJS injects with `@Res()`:

```ts
@Post("chat")
async chat(@Body() body: { id: string; messages: UIMessage[] }, @Res() res: Response) {
  await pipeCopilotChatToResponse(res, { agent: this.copilot.agent(support), messages: body.messages, conversationId: body.id });
}
```

Inject the core instance with `@InjectCopilot()` and pass a definition, or use the
`AgentHandle` of a functional agent (`@InjectAgent("support")`).
<!-- /only -->
<!-- only:fastify -->
## Fastify

The [Fastify plugin](fastify.md#usechat-and-approvals)'s `./ai` entry point wraps these
helpers: `reply.copilotChat({ agent: "support" })` answers a `useChat` request with the request's
context and disconnect signal, a per-user conversation id, and an app-wide approval store, and
`reply.copilotUIStream(events)` streams any event iterable.
<!-- /only -->

## Tool approvals

When a permission resolves to `ask-user` (`permissions.askUser()`, or an AI SDK `tool()` with
`needsApproval`), the client receives a `tool-approval-request`. The response then finishes
while the Copilot turn waits on the server. The next request carries the answer and resumes the
same turn, which continues the same assistant message:

```tsx
import { useChat } from "@ai-sdk/react";
import { lastAssistantMessageIsCompleteWithApprovalResponses } from "ai";

const { messages, sendMessage, addToolApprovalResponse } = useChat({
  sendAutomaticallyWhen: lastAssistantMessageIsCompleteWithApprovalResponses,
});
// For a tool part in state "approval-requested":
addToolApprovalResponse({ id: part.approval.id, approved: true });
```

Suspended turns wait in a process-local `CopilotApprovalStore`:

- The default is `defaultApprovalStore`. Pass `approvals: createApprovalStore({ ttlMs })` to use
  your own; `store.suspendedTurns` counts waiting turns and `store.clear()` aborts them all
  (for example at shutdown).
- If an approval is not answered within `ttlMs` (default 10 minutes), the turn is aborted. An
  answer to an unknown or expired approval produces a `tool-output-error`.
- A new user message in the same conversation supersedes a turn that is still waiting.
- With several replicas, use sticky sessions so each conversation reaches the same process.
- Core's [`approvalWindow`](configuration.md#copilotoptions) (default 120 s) still applies: an
  unanswered approval is denied when it runs out. Raise it to match `ttlMs` for slow reviewers.

## UI message stream mapping

For full control, map an event stream yourself:

```ts
import { toUIMessageStreamResponse } from "@copilot-sdk-integrations/ai";

const events = copilot.agent(support).stream({ prompt, conversationId });
return toUIMessageStreamResponse(events, {
  // Inline approvals, answered from a WebSocket message or a separate endpoint
  onApprovalRequest: async (approval) => approval.respond({ approved: await isAllowed(approval) }),
});
```

| Copilot event | UI message chunks |
| --- | --- |
| Turn start / end | `start` / `finish` |
| Text | `text-start`, `text-delta`, `text-end` |
| Reasoning | `reasoning-start`, `reasoning-delta`, `reasoning-end` |
| Tool call | `tool-input-available` |
| Tool result | `tool-output-available`, `tool-output-error`, or `tool-output-denied` |
| Permission request | `tool-approval-request` |
| Permission answer | `tool-approval-response` |
| Failure | `error`, then `finish` with reason `error` |

| Function | Result |
| --- | --- |
| `toUIMessageStream(events, options?)` | `ReadableStream<UIMessageChunk>` |
| `toUIMessageStreamResponse(events, options?)` | A `Response` (alias `createCopilotUIMessageStreamResponse`) |
| `pipeUIMessageStreamToResponse(res, stream, init?)` | Writes a chunk stream to a `ServerResponse` |
| `pipeCopilotEventsToResponse(res, events, options?)` | Maps and writes in one call |

| Option | Default | Description |
| --- | --- | --- |
| `messageId` | generated | Id of the assistant message. |
| `sendReasoning` | `true` | Send reasoning parts. |
| `sendStart` / `sendFinish` | `true` | Send the `start` / `finish` chunk. |
| `dynamicTools` | `false` | Emit `dynamic-tool` parts instead of typed `tool-<name>` parts. |
| `messageMetadata(info)` | `{ sessionId, model, usage }` on finish | Metadata for the `start` and `finish` chunks. |
| `onError(error)` | `defaultErrorText` | The `errorText` sent to the client: the message of a core `CopilotIntegrationError`, otherwise "An error occurred.", so internal details don't leak. |
| `onApprovalRequest(approval)` | none | Called for every `ask-user` permission after the `tool-approval-request` chunk. |
| `generateId` | AI SDK `generateId` | Id generator for message and part ids. |

## AI SDK provider

```ts
import { generateText, streamText } from "ai";
import { createCopilotProvider } from "@copilot-sdk-integrations/ai";

const copilotAI = createCopilotProvider({ copilot, agent: advisor });

const { text, usage } = await generateText({ model: copilotAI(), prompt: "What is libuv?" });

const result = streamText({
  model: copilotAI("gpt-5"), // overrides the agent's model; "default" keeps it
  messages,
  providerOptions: { copilot: { conversationId: chatId } }, // server-side history
});
```

Provider settings (`createCopilotProvider`, alias `copilot`) and model settings:

| Setting | Description |
| --- | --- |
| `copilot` | The core `Copilot` instance (provider only). |
| `agent` | Agent definition backing the model. Default `{ name: "ai-sdk" }`: no tools, deny-all permissions. |
| `conversationId` | Default conversation key; override per call with `providerOptions.copilot.conversationId`. |
| `principal` | Principal for session ids; override per call with `providerOptions.copilot.principal`. |
| `tools` | Extra Copilot tools for every call (core tools or AI SDK `tool()` records). |
| `context` | Merged into the turn's `CopilotContext`. |
| `timeout` | Per-turn timeout in ms. |
| `onPermissionRequest` | Answers `ask-user` permissions. Default: deny. |

`provider(modelId?, settings?)` and `provider.languageModel(...)` use the provider's agent;
`provider.agent(definition, settings?)` uses another definition with its own model. Embedding
and image models throw `NoSuchModelError`.

- **System prompt.** `system` messages are appended to the agent's instructions.
- **Tools** belong to the Copilot agent. Copilot runs them itself and reports provider-executed,
  dynamic `tool-call` and `tool-result` parts. Passing `tools` to `generateText` or `streamText`
  throws `UnsupportedFunctionalityError`.
- **Structured output.** `Output.object` and other JSON response formats run as Copilot
  [structured output](agents.md#structured-output).
- **Ignored settings.** `maxOutputTokens`, `temperature`, `stopSequences`, `topP`, `topK`,
  `presencePenalty`, `frequencyPenalty`, and `seed` are ignored with a warning.
- **Prompt conversion.** With a conversation, only the latest user messages are sent. Without
  one, a multi-message prompt is sent as a text transcript. File parts become a placeholder.
  `toCopilotPrompt(prompt, "latest" | "transcript")` exposes the conversion.

## AI SDK agents

```ts
import { createAgentUIStreamResponse } from "ai";
import { createCopilotAgent } from "@copilot-sdk-integrations/ai";

const agent = createCopilotAgent({ copilot, agent: advisor });
await agent.generate({ prompt: "Hello" });
return createAgentUIStreamResponse({ agent, uiMessages: messages });
```

`createCopilotAgent(settings)` accepts the provider settings plus `modelId` and the other AI
SDK agent settings, and returns a `ToolLoopAgent` backed by a Copilot model.
