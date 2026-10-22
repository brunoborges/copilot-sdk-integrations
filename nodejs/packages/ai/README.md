# @copilot-sdk-integrations/ai

This package connects [`@copilot-sdk-integrations/core`](../core) to the [Vercel AI SDK](https://ai-sdk.dev) v7 (`ai@^7`). It has three parts:

- **UI message streams**: maps Copilot agent events to the AI SDK UI message stream protocol, so `useChat` frontends work unchanged. Tool approvals are included.
- **`useChat` handler**: `streamCopilotChat` takes a `useChat` request and turns it into a Copilot turn. History stays on the server, keyed by the chat id. It also handles the approval round trip.
- **AI SDK provider**: `createCopilotProvider` (alias `copilot`) returns a `LanguageModelV4`, so you can use `generateText` and `streamText` with Copilot agents. `createCopilotAgent` returns an AI SDK `Agent`.

```bash
npm install @copilot-sdk-integrations/ai @copilot-sdk-integrations/core ai
```

## Chat UI (`useChat`)

### Next.js route handler

```ts
// app/api/chat/route.ts
import { createCopilot, defineAgent, permissions } from "@copilot-sdk-integrations/core";
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

### Express / NestJS

```ts
import { pipeCopilotChatToResponse } from "@copilot-sdk-integrations/ai";

// Express
app.post("/api/chat", express.json(), async (req, res) => {
  await pipeCopilotChatToResponse(res, { agent: copilot.agent(support), messages: req.body.messages, conversationId: req.body.id });
});

// NestJS
@Post("chat")
async chat(@Body() body: { id: string; messages: UIMessage[] }, @Res() res: Response) {
  await pipeCopilotChatToResponse(res, { agent: this.copilot.agent(support), messages: body.messages, conversationId: body.id });
}
```

### Client with tool approvals

When a permission resolves to `"ask-user"` (`permissions.askUser()`, or an AI SDK tool with `needsApproval`), the client receives a `tool-approval-request`. The response then finishes, and the Copilot turn waits on the server for the user's answer. The next request carries that answer and resumes the same turn, which continues the same assistant message.

```tsx
import { useChat } from "@ai-sdk/react";
import { lastAssistantMessageIsCompleteWithApprovalResponses } from "ai";

const { messages, sendMessage, addToolApprovalResponse } = useChat({
  sendAutomaticallyWhen: lastAssistantMessageIsCompleteWithApprovalResponses,
});
// For a tool part in state "approval-requested":
addToolApprovalResponse({ id: part.approval.id, approved: true });
```

Suspended turns are held in a process-local `CopilotApprovalStore`:

- The default is `defaultApprovalStore`. Pass `approvals: createApprovalStore({ ttlMs })` to use your own.
- If an approval is not answered within `ttlMs` (default 10 minutes), the turn is aborted.
- Only the owner can answer an approval. The store records the `conversationId` and the principal (`context.principal`, else the ambient `getCopilotContext()` principal) of the request that started the turn. A response from another conversation or principal is treated like an unknown approval: the client gets the same "expired or unknown" tool error, and the suspended turn is left untouched. Approvals of ephemeral turns (no `conversationId`) match on the principal alone, so set a principal when you use them.
- A new user message in the same conversation (from the same principal) replaces a turn that is still waiting.
- With several replicas, use sticky sessions so that each conversation always reaches the same process.
- Core's `approvalWindow` (default 120 s) still applies: an unanswered approval is denied when it runs out.

### Lower-level mapping

```ts
import { toUIMessageStream, toUIMessageStreamResponse, pipeCopilotEventsToResponse } from "@copilot-sdk-integrations/ai";

const events = copilot.agent(support).stream({ prompt, conversationId });
return toUIMessageStreamResponse(events, {
  onApprovalRequest: async (approval) => approval.respond({ approved: await isAllowed(approval) }), // inline approvals
});
```

`toUIMessageStream` produces these chunks:

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

- The `finish` chunk carries `messageMetadata`: `{ sessionId, model, usage }`. Override it with the `messageMetadata` option.
- Cancelling the stream, for example when the client disconnects, aborts the Copilot turn.

## AI SDK provider

```ts
import { generateText, streamText } from "ai";
import { createCopilotProvider } from "@copilot-sdk-integrations/ai";

const copilotAI = createCopilotProvider({ copilot, agent: advisor });

const { text, usage } = await generateText({ model: copilotAI(), prompt: "What is libuv?" });

const result = streamText({
  model: copilotAI("gpt-5"), // overrides the agent's model; "default" keeps it
  messages,
  providerOptions: { copilot: { conversationId: chatId } }, // server-side history: only new user messages are sent
});
```

- **System prompt**: `system` messages are appended to the agent's `instructions`.
- **Tools** are part of the Copilot agent. Define them with `defineAgent({ tools })` or the `tools` model setting.
  - Copilot runs them itself and reports them as provider-executed, dynamic `tool-call` and `tool-result` parts.
  - Passing `tools` to `generateText` or `streamText` throws `UnsupportedFunctionalityError`.
- **Permissions**: `"ask-user"` permissions go to the `onPermissionRequest` setting. Without that setting they are denied.
- **Structured output**: `Output.object` and other JSON response formats run as Copilot structured output.
- **Unsupported settings**: `temperature`, `topP`, `maxOutputTokens`, `seed`, and the other sampling settings are ignored, and a warning is reported.
- **Prompt conversion**:
  - Without a `conversationId`, a multi-message prompt is sent as a text transcript.
  - File parts are replaced with a placeholder.

```ts
import { createAgentUIStreamResponse } from "ai";
import { createCopilotAgent } from "@copilot-sdk-integrations/ai";

const agent = createCopilotAgent({ copilot, agent: advisor });
await agent.generate({ prompt: "Hello" });
return createAgentUIStreamResponse({ agent, uiMessages: messages });
```
