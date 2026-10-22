# NestJS chat example

A realistic [NestJS](https://nestjs.com/) app that hosts a "Node.js advisor" agent with
`@copilot-sdk-integrations/nestjs`. The agent is the Node counterpart of the Spring Boot
`spring-advisor` sample. The app also includes a minimal React UI (`web/`) built on AI SDK
`useChat`, with buttons for approving tools.

The app deliberately keeps the **default `nest new` setup**: it compiles to **CommonJS**
(`"module": "commonjs"`, `emitDecoratorMetadata`, no `"type": "module"`). This shows that CJS
Nest apps can consume the ESM-only `@copilot-sdk-integrations/*` packages through `require(esm)`,
which works without flags on Node `^20.19.0 || >=22.12.0`.

## What it demonstrates

| Feature | Where |
| --- | --- |
| `CopilotModule.forRootAsync` with `ConfigService` (GitHub token / BYOK / model / lazy startup / limits) | `src/copilot.config.ts` |
| A declarative agent: an abstract class with `@CopilotAgent`, string, `@Returns(zod)` structured output and `Observable` streaming methods | `src/advisor/advisor.agent.ts` |
| `@CopilotTool` methods on an ordinary provider, reading the caller from `invocation.context` | `src/advisor/advisor.tools.ts` |
| An injectable permission policy: approve reads, **ask the user** before `saveNote`, deny everything else (including built-in shell, file and URL tools) | `src/advisor/advisor.policy.ts` |
| Per-user identity: a global guard turns `x-user-id` into `request.user`, which becomes the Copilot principal. Conversations are scoped per user | `src/auth/*` |
| Multi-turn conversations, structured output, and SSE streaming with `toSseEvents` and an HTTP permission answer | `src/advisor/advisor.controller.ts` |
| An AI SDK `useChat` endpoint with tool approvals (`pipeCopilotChatToResponse` + `CopilotApprovalStore`) | `src/advisor/chat.controller.ts` |
| Terminus health check (`CopilotHealthIndicator`) that doesn't start the runtime | `src/health/health.controller.ts` |
| HTTP error mapping through the module's `CopilotExceptionFilter` (session error → 502, timeout → 504, …) | global, from `CopilotModule` |
| Credential-free e2e tests with `CopilotTestingModule` | `test/app.e2e.test.ts` |

## Prerequisites

- Node.js `^20.19.0 || >=22.12.0`. Older versions can't `require()` ES modules.
- Copilot credentials, using one of these:
  - the [GitHub Copilot CLI](https://docs.github.com/copilot/how-tos/set-up/install-copilot-cli) installed and logged in (`copilot` then `/login`);
  - `GITHUB_TOKEN` set to a token with Copilot access;
  - `COPILOT_CLI_URL` pointing at a running Copilot runtime server;
  - BYOK: `BYOK_BASE_URL`, `BYOK_API_KEY`, `BYOK_TYPE` (`openai` | `azure` | `anthropic`) and `COPILOT_MODEL`.

Other settings:

- `PORT` (default `3000`).
- `COPILOT_MODEL`.
- `COPILOT_STARTUP`: `lazy` (the default) starts the runtime on the first request. `eager` starts it during boot and fails fast.

## Run

From `nodejs/`:

```sh
npm install
npm run build                           # packages, examples and the web UI
npm start -w examples/nestjs-chat       # API on http://localhost:3000
npm run dev -w examples/nestjs-chat/web # UI on http://localhost:5173 (proxies /api to :3000)
```

To point the UI at another server (for example the [Express example](../express)), run
`API_URL=http://localhost:4000 npm run dev -w examples/nestjs-chat/web`.

### API

Every `/api/*` route requires an `x-user-id` header. Without it the route returns 401. `/health`
is public.

| Method & path | Description |
| --- | --- |
| `GET /health` | Terminus health (`copilot: up`, even before the runtime starts) |
| `POST /api/conversations` `{question}` | Starts a conversation and returns `{conversationId, answer}` (201) |
| `POST /api/conversations/:id/messages` `{question}` | Continues an existing conversation. Returns 404 for unknown conversations or another user's conversations |
| `GET /api/conversations/:id/stream?q=` | Streams agent events as SSE (`turn-start`, `text-delta`, `tool-start`, `permission-request`, …, `done`) |
| `POST /api/conversations/:id/permissions/:requestId` `{approved, feedback?}` | Answers a pending `permission-request` (204, or 404) |
| `GET /api/explain?term=&audience=` | Returns structured output validated by zod (`{term, summary, level, related}`) |
| `GET /api/notes` | Returns the caller's saved notes |
| `POST /api/chat` | The AI SDK `useChat` endpoint (UI message stream, tool approvals) |

```sh
curl localhost:3000/health
curl -X POST localhost:3000/api/conversations -H 'x-user-id: ada' -H 'content-type: application/json' \
  -d '{"question":"What is libuv?"}'
curl 'localhost:3000/api/explain?term=event%20loop&audience=beginner' -H 'x-user-id: ada'
curl -N 'localhost:3000/api/conversations/demo/stream?q=Remember%20that%20streams%20need%20pipeline()' -H 'x-user-id: ada'
# ...then approve the saveNote call using the requestId from the permission-request event:
curl -X POST localhost:3000/api/conversations/demo/permissions/<requestId> -H 'x-user-id: ada' \
  -H 'content-type: application/json' -d '{"approved":true}'
```

## Test

```sh
npm test -w examples/nestjs-chat
```

The tests override `AppCopilotModule` with
`CopilotTestingModule.forRoot(script)` from `@copilot-sdk-integrations/nestjs/testing`, so they
need no credentials or runtime process. They run on Vitest with `unplugin-swc`, which provides
the decorator metadata.

## Notes

- Approvals live in a process-local store (`createApprovalStore`), and a paused turn lives in the
  process that started it. If you run more than one instance, route a conversation's requests to
  the same instance (sticky sessions).
- The `x-user-id` guard is for demonstration only. Use real authentication (for example JWT or
  sessions) and set `request.user` from it.
