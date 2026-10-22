# Copilot for Vercel

Status: **design**. Nothing is implemented or published yet.

An integration module (working name `copilot-vercel`) that makes it simple to run
GitHub Copilot agents and AI usage, built on the
[Copilot SDK for Node.js/TypeScript](https://github.com/github/copilot-sdk/tree/main/nodejs),
in applications deployed on [Vercel](https://vercel.com).

It takes its capabilities from the [Spring Boot starter](../spring-boot/) and expresses
them in Vercel idioms:

- **AI SDK native**: a Copilot agent is an AI SDK `Agent`, and `copilot("model")` is an
  AI SDK provider. `useChat`, `tool()`, Zod schemas, and tool-approval UIs work unchanged.
- **Serverless correct**: lazy runtime singleton on Fluid compute, admission limits,
  per-conversation locks, turn deadlines, and session state stored in Redis, Blob, or
  Postgres.
- **Safe by default**: permissions denied unless configured; built-in shell and file
  tools run only inside Vercel Sandbox, never on the Function.
- **Platform integrated**: AI Gateway with OIDC for BYOK models, Vercel Sandbox for coding
  agents, OpenTelemetry through `@vercel/otel`, and `withCopilot()` to package the native
  runtime within function size limits.

```ts
// app/api/chat/route.ts
import { copilotChatRoute } from "copilot-vercel/next";
import { support } from "@/lib/agents";

export const maxDuration = 300;
export const { POST } = copilotChatRoute({ agent: support, authorize });
```

Read the full design, including the architecture, API sketches, roadmap, and open
questions, in [DESIGN.md](DESIGN.md).
