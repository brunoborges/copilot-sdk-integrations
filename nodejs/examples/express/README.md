# Express example

A small plain [Express 5](https://expressjs.com/) app (ESM) that uses only
`@copilot-sdk-integrations/agent-core` and `@copilot-sdk-integrations/ai`. It runs a "Node.js advisor"
agent, which is the Node counterpart of the Spring Boot `spring-advisor` sample.

## What it demonstrates

| Feature | Where |
| --- | --- |
| `createCopilot` (lazy start), graceful shutdown | `src/server.ts` |
| `defineAgent` with instructions, tools and a permission policy | `src/advisor.ts` |
| A core tool (`defineCopilotTool` + zod) approved by policy | `describeTerm` in `src/advisor.ts` |
| An AI SDK `tool()` with `needsApproval` (human-in-the-loop) | `saveNote` in `src/advisor.ts` |
| Per-user identity passed as turn context (`context.principal`) and read by tools via `getCopilotContext()` | `src/app.ts` |
| AI SDK `useChat` endpoint with tool approvals (`pipeCopilotChatToResponse` + approval store) | `POST /api/chat` |
| One-shot UI message stream over SSE (`pipeCopilotEventsToResponse`) | `GET /api/stream?q=` |
| Non-streaming request/response (`agent.send`) | `POST /api/ask` |
| Health without starting the runtime (`copilot.health()`) | `GET /health` |
| Tests with no credentials or runtime process (`createTestCopilot`) | `test/app.test.ts` |

The policy (`permissions.allowCustomTools(["describeTerm", "saveNote"])`) approves only the
app's own tools. Built-in shell, file and URL tools are denied. `needsApproval` then turns the
`saveNote` approval into a request that the user answers in the browser.

Identity in this demo comes from the `x-user-id` header and defaults to `anonymous`. Replace it
with real authentication. Conversation ids are prefixed with the user so that two users can't
share a chat.

## Prerequisites

- Node.js `^20.19.0 || >=22.12.0`.
- Copilot credentials, using one of these:
  - the [GitHub Copilot CLI](https://docs.github.com/copilot/how-tos/set-up/install-copilot-cli) installed and logged in (`copilot` then `/login`);
  - `GITHUB_TOKEN` set to a token with Copilot access;
  - `COPILOT_CLI_URL` pointing at a running Copilot runtime server.

## Run

From `nodejs/`:

```sh
npm install
npm run build          # builds the packages and this example
npm start -w examples/express    # PORT defaults to 3000
```

```sh
curl localhost:3000/health
curl -X POST localhost:3000/api/ask -H 'content-type: application/json' -H 'x-user-id: ada' \
  -d '{"question":"What is libuv?"}'
curl -N 'localhost:3000/api/stream?q=Explain%20the%20event%20loop'
curl localhost:3000/api/notes -H 'x-user-id: ada'
```

`POST /api/chat` speaks the AI SDK UI message stream protocol. Point any `useChat` client at it.
For a ready-made one, see the React UI in [`../nestjs-chat/web`](../nestjs-chat/web) and run it
with `API_URL=http://localhost:3000`.

## Test

```sh
npm test -w examples/express
```

The tests use a scripted runtime from `@copilot-sdk-integrations/agent-core/testing`. They need no
credentials and no network access.
