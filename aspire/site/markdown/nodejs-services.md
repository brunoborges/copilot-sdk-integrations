---
title: Node.js
---

# Node.js

Node.js services connect to the AppHost's runtime with the
Node.js integrations (`@copilot-sdk-integrations/agent-core`, or the
[NestJS](https://brunoborges.github.io/copilot-sdk-integrations/nestjs/), [Fastify](https://brunoborges.github.io/copilot-sdk-integrations/fastify/), and AI SDK packages built on it) or with the
[Microsoft Copilot SDK for Node.js](https://github.com/microsoft/copilot-sdk/tree/main/nodejs)
(`@microsoft/copilot-sdk`) directly.

## AppHost

The [Aspire JavaScript hosting integration](https://aspire.dev/integrations/frameworks/javascript/)
(`Aspire.Hosting.JavaScript`) runs Node.js apps:

```bash
aspire add javascript
```

```cs
var copilot = builder.AddCopilot("copilot");
var chat = copilot.AddModel("chat", "gpt-5.4");

builder.AddNodeApp("web", "../web", "server.js")      // runs a script with node
       .WithReference(chat)
       .WaitFor(chat);

builder.AddJavaScriptApp("nest", "../nest", "start")  // runs a package.json script ("dev" by default)
       .WithReference(chat)
       .WaitFor(chat);
```

Both apps receive `CHAT_HOST`, `CHAT_PORT`, `CHAT_CONNECTIONTOKEN`, and `CHAT_MODEL`, among others.
See the [Environment variable reference](environment-variables.md).

## The runtime address

The integrations' `cliUrl` option accepts a port, `host:port`, `[ipv6]:port`, or
`http(s)://host:port`. It doesn't accept `tcp://`, so build it from `CHAT_HOST` and `CHAT_PORT`
rather than using `CHAT_ENDPOINT`:

```ts
const cliUrl = `${process.env.CHAT_HOST}:${process.env.CHAT_PORT}`;
```

The SDK's `RuntimeConnection.forTcp(host, port, { connectionToken })` takes the host (without a
scheme or IPv6 brackets) and the port as separate arguments, so pass `CHAT_HOST` and `CHAT_PORT`
to it directly.

## With `@copilot-sdk-integrations/agent-core`

`createCopilot({ cliUrl })` connects to an external runtime, but it has no option for the connection
token. Pass the token through `configureClient`, which receives the SDK client options before the
client is created:

```ts
import { RuntimeConnection } from "@microsoft/copilot-sdk";
import { createCopilot, defineAgent } from "@copilot-sdk-integrations/agent-core";

const cliUrl = `${process.env.CHAT_HOST}:${process.env.CHAT_PORT}`;

const copilot = createCopilot({
  cliUrl,
  configureClient: (options) => ({
    ...options,
    runtime: RuntimeConnection.forTcp(process.env.CHAT_HOST!, Number(process.env.CHAT_PORT), {
      connectionToken: process.env.CHAT_CONNECTIONTOKEN,
    }),
  }),
});

const support = defineAgent({
  name: "support",
  instructions: "You help customers with orders.",
  model: process.env.CHAT_MODEL,
});

const text = await copilot.agent(support).send({ prompt: "Where is order 42?" });
```

Keep `cliUrl` set: it tells the integration that the runtime is external. Don't combine it with
`cliPath` or `useLoggedInUser`; the integration rejects both, because an external runtime manages its
own sign-in.

When the AppHost sets a reasoning effort or MCP servers, pass them to the agent:

```ts
const support = defineAgent({
  name: "support",
  instructions: "Answer questions from the product docs.",
  model: process.env.CHAT_MODEL,
  reasoningEffort: process.env.Copilot__ReasoningEffort as "low" | "medium" | "high" | "xhigh" | undefined,
  mcpServers: {
    docs: {
      type: "http",
      url: process.env.Copilot__McpServers__docs__Url!,
      tools: ["search_docs", "get_page"],
    },
  },
});
```

### NestJS

`CopilotModule` takes the same options, so the same `cliUrl` and `configureClient` work there:

```ts
import { Module } from "@nestjs/common";
import { RuntimeConnection } from "@microsoft/copilot-sdk";
import { CopilotModule } from "@copilot-sdk-integrations/nestjs";

const cliUrl = `${process.env.CHAT_HOST}:${process.env.CHAT_PORT}`;

@Module({
  imports: [
    CopilotModule.forRoot({
      cliUrl,
      configureClient: (options) => ({
        ...options,
        runtime: RuntimeConnection.forTcp(process.env.CHAT_HOST!, Number(process.env.CHAT_PORT), {
          connectionToken: process.env.CHAT_CONNECTIONTOKEN,
        }),
      }),
      agents: { support: { model: process.env.CHAT_MODEL } },
    }),
  ],
})
export class AppModule {}
```

`agents` holds per-agent overrides keyed by agent name, so the model from the AppHost applies to the
`@CopilotAgent` named `support`.

## With the Microsoft Copilot SDK directly

```ts
import { CopilotClient, RuntimeConnection } from "@microsoft/copilot-sdk";

const client = new CopilotClient({
  mode: "empty",
  runtime: RuntimeConnection.forTcp(process.env.CHAT_HOST!, Number(process.env.CHAT_PORT), {
    connectionToken: process.env.CHAT_CONNECTIONTOKEN,
  }),
});

const session = await client.createSession({
  model: process.env.CHAT_MODEL,
  tools: { availableTools: [] },
});
```

`mode` is required. `"empty"` suits a service that shares the runtime with other services: sessions
get no built-in tools, and each session must list the tools it allows in `tools.availableTools`
(`custom:<name>` for your own tools, `mcp:*` for MCP tools, or `[]` for none). Use
`mode: "coding-agent"` for the CLI's built-in tools, which run with the runtime's access to its host.
Don't set `github.token` or `github.useLoggedInUser` on the client; the SDK rejects them for an
external runtime.

## BYOK providers

Provider settings are only in `ConnectionStrings__chat`. Parse it and pass the values as the agent's
`provider`:

```ts
const parts = Object.fromEntries(
  (process.env.ConnectionStrings__chat ?? "")
    .split(";")
    .filter(Boolean)
    .map((pair) => {
      const i = pair.indexOf("=");
      return [pair.slice(0, i), pair.slice(i + 1)];
    }),
);

const support = defineAgent({
  name: "support",
  instructions: "You help customers with orders.",
  model: parts.Model,
  provider: parts.Provider
    ? {
        type: parts.Provider as "openai" | "azure" | "anthropic",
        baseUrl: parts.ProviderEndpoint,
        apiKey: parts.ProviderKey || undefined,
        wireApi: parts.WireApi as "completions" | "responses" | undefined,
      }
    : undefined,
});
```

See [Models and BYOK providers](models.md#bring-your-own-key-providers).
