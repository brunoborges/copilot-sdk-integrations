# Fastify example

A small [Fastify 5](https://fastify.dev/) app (ESM) built on the
[`@copilot-sdk-integrations/fastify`](../../packages/fastify) plugin. It runs the same "Node.js
advisor" agent as the [Express example](../express), the Node counterpart of the Spring Boot
`spring-advisor` sample, written the Fastify way: an app factory, plugins, route schemas, and
`app.inject()` tests.

## What it demonstrates

| Feature | Where |
| --- | --- |
| `buildApp(opts)` app factory that registers `copilotPlugin` (lazy start) and `copilotAiPlugin` | `src/app.ts` |
| Graceful shutdown: `app.close()` on `SIGINT`/`SIGTERM` drains turns and stops the runtime | `src/server.ts` |
| `defineAgent` with instructions, tools and a permission policy, typed through `CopilotAgentRegistry` | `src/advisor.ts`, `src/app.ts` |
| A core tool (`defineCopilotTool` + zod) approved by policy | `describeTerm` in `src/advisor.ts` |
| An AI SDK `tool()` with `needsApproval` (human-in-the-loop) | `saveNote` in `src/advisor.ts` |
| Per-user identity captured by the plugin's context hook (`context.principal`) and read by tools via `getCopilotContext()` | `src/app.ts` |
| AI SDK `useChat` endpoint with owner-scoped tool approvals (`reply.copilotChat`) | `POST /api/chat` |
| One-shot UI message stream over SSE (`reply.copilotUIStream`) | `GET /api/stream?q=` |
| Raw Copilot events over SSE (`reply.copilotEvents`) | `GET /api/events?q=` |
| Non-streaming request/response (`request.copilot.agent(...).send`) with a route body schema | `POST /api/ask` |
| Copilot errors mapped to HTTP by the plugin's error handler (429, 503, ...) | all routes |
| Health without starting the runtime (plugin `health` route) | `GET /health` |
| Tests with no credentials or runtime process (`buildCopilotTestApp` + `app.inject()`) | `test/app.test.ts` |

The policy (`permissions.allowCustomTools(["describeTerm", "saveNote"])`) approves only the
app's own tools. Built-in shell, file and URL tools are denied. `needsApproval` then turns the
`saveNote` approval into a request that the user answers in the browser. Only the user who owns
the conversation can answer it.

Identity in this demo comes from the `x-user-id` header and defaults to `anonymous`. Replace it
with real authentication (for example `@fastify/jwt`, whose `request.user` the plugin reads by
default). `useChat` conversation ids are prefixed with the user so that two users can't share a
chat.

Requests are validated by Fastify route schemas, so a missing `question` or `q` is a `400` before
any Copilot call.

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
npm run build                     # builds the packages and this example
npm start -w examples/fastify     # PORT defaults to 3000, HOST to localhost
```

```sh
curl localhost:3000/health
curl -X POST localhost:3000/api/ask -H 'content-type: application/json' -H 'x-user-id: ada' \
  -d '{"question":"What is libuv?"}'
curl -N 'localhost:3000/api/stream?q=Explain%20the%20event%20loop'
curl -N 'localhost:3000/api/events?q=Explain%20the%20event%20loop'
curl localhost:3000/api/notes -H 'x-user-id: ada'
curl -X POST localhost:3000/api/ask -H 'content-type: application/json' -d '{}'   # 400
```

`POST /api/chat` speaks the AI SDK UI message stream protocol. Point any `useChat` client at it.
For a ready-made one, see the React UI in [`../nestjs-chat/web`](../nestjs-chat/web) and run it
with `API_URL=http://localhost:3000`.

## Test

```sh
npm test -w examples/fastify
```

The tests build the app with `buildCopilotTestApp` from `@copilot-sdk-integrations/fastify/testing`
(a scripted runtime) and call it with `app.inject()`. They need no credentials and no network
access.
