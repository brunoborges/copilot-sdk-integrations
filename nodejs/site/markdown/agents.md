---
title: Agents
---

# Agents

An agent is a named, immutable definition: instructions, model, tools, permissions, and hooks.
Every turn opens (or resumes) a Copilot session configured from it.

**In this chapter**

- [Agent definitions](#agent-definitions)
- [System instructions](#system-instructions)
- [Models and reasoning](#models-and-reasoning)
- [BYOK providers](#byok-providers)
- [MCP servers](#mcp-servers)
- [Session customization](#session-customization)
- [NestJS agent classes](#nestjs-agent-classes)
- [Prompt templates](#prompt-templates)
- [Return types](#return-types)
- [Structured output](#structured-output)
- [Functional agents in NestJS](#functional-agents-in-nestjs)
- [Per-agent configuration overrides](#per-agent-configuration-overrides)

## Agent definitions

```ts
const advisor = defineAgent({
  name: "advisor",
  description: "Answers questions about our Node.js services",
  instructions: "Answer briefly. Cite file paths when relevant.",
  model: "gpt-5",
  reasoningEffort: "medium",
  tools: [searchDocs],
  permissions: permissions.allowCustomTools(),
});
```

| Field | Purpose |
| --- | --- |
| `name` | Required. `^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$`. Part of the session id, so renaming an agent orphans its conversations. |
| `description` | Human-readable description. |
| `instructions` | System instructions; see below. |
| `model` | Model id. Default: the runtime's default model. |
| `reasoningEffort` | `low`, `medium`, `high`, or `xhigh`. |
| `provider` | A BYOK [`ProviderConfig`](#byok-providers). |
| `tools` | [Tools](tools.md) the agent can call. |
| `mcpServers` | [MCP servers](#mcp-servers), keyed by name. |
| `availableTools` / `excludedTools` | [Tool filters](tools.md#tool-filters); mutually exclusive. |
| `builtInTools` | Expose the runtime's built-in tools (shell, file edits, URL fetch). Default `false`. |
| `permissions` | A [permission policy](governance.md#permission-policies). Default: deny everything. |
| `hooks` | SDK [session hooks](governance.md#hooks). |
| `customAgents` | SDK custom (sub-)agent configurations. |
| `infiniteSessions` | Enable the runtime's infinite-session context compaction. |
| `configureSession` | Escape hatch to adjust the final SDK session config. |

`defineAgent` validates the definition and throws `CopilotConfigurationError` with an
`Agent "<name>": ...` prefix on invalid input, then freezes it. Definitions are plain data,
so they can be shared between handles, tests, and the AI SDK provider.

## System instructions

| Form | Effect |
| --- | --- |
| `"text"` | Appended to the runtime's default system message. |
| `{ mode: "append", content }` | Same as a string. |
| `{ mode: "replace", content }` | Replaces the default system message. You lose the runtime's tool-use guidance. |
| `{ mode: "customize", ... }` | The SDK's section-level customization (`SystemMessageConfig`). |

## Models and reasoning

`model` and `reasoningEffort` are passed to the session. Use `copilot.client()` for raw SDK
calls such as listing models. In NestJS, override them per environment with
[`agents.<name>`](#per-agent-configuration-overrides) instead of editing decorators.

## BYOK providers

Bring your own model keys with the SDK's `ProviderConfig`:

```ts
defineAgent({
  name: "advisor",
  model: "gpt-4.1",
  provider: { type: "azure", baseUrl: process.env.FOUNDRY_URL!, apiKey: process.env.FOUNDRY_KEY },
});
```

`type` is `openai` (default, any OpenAI-compatible API), `azure`, or `anthropic`; local
servers such as Ollama need only `baseUrl`. In NestJS, declare named providers once and
reference them by name:

```ts
CopilotModule.forRoot({
  providers: { foundry: { type: "azure", baseUrl: "...", apiKey: "..." } },
});

@CopilotAgent({ name: "advisor", provider: "foundry" })
export abstract class AdvisorAgent { /* ... */ }
```

An unknown provider name fails at bootstrap.

## MCP servers

```ts
defineAgent({
  name: "dev",
  mcpServers: {
    github: { type: "http", url: "https://api.githubcopilot.com/mcp/", tools: ["*"] },
    files: { command: "npx", args: ["-y", "@modelcontextprotocol/server-filesystem", "./docs"], tools: ["*"] },
  },
  permissions: permissions.allowMcpServers(["github", "files"]),
});
```

Local servers use `command`, `args`, `env`, and `workingDirectory`; remote servers use
`type: "http"` or `"sse"`, `url`, and `headers`.

Remote `headers` and local `env` can depend on the request: pass a function of the
[`CopilotContext`](context-and-identity.md), or a map whose values are such functions. They are
resolved each time a session is created or resumed, and `undefined` values are dropped:

```ts
mcpServers: {
  github: {
    type: "http",
    url: "https://api.githubcopilot.com/mcp/",
    tools: ["*"],
    headers: { Authorization: (ctx) => (ctx.githubToken ? `Bearer ${ctx.githubToken}` : undefined) },
  },
},
```

MCP tool calls go through the
[permission policy](governance.md). In NestJS, define servers once in the module's
`mcpServers` option and list their names in `@CopilotAgent({ mcpServers: ["github"] })`.

## Session customization

`configureSession(config, context)` receives the final SDK `SessionConfig` and the
[`CopilotContext`](context-and-identity.md) and returns the config to use. It must not change
`sessionId` or remove the permission handler; both are rejected. In NestJS, providers bound
to `COPILOT_SESSION_CUSTOMIZER` run after it with `(config, context, agentDefinition)`.

## NestJS agent classes

```ts
@CopilotAgent({ name: "support", instructions: "...", tools: [OrdersTools], permissions: permissions.allowCustomTools() })
export abstract class SupportAgent {
  @UserMessage()
  answer(@ConversationId() conversationId: string, @Message() question: string): Promise<string> {
    return implementedByCopilot();
  }
}
```

`@CopilotAgent(options)` accepts every definition field. `name` defaults to the class name;
`tools` accepts `@CopilotTool` provider classes and core tool inputs; `provider` and
`mcpServers` accept names from the module options; `permissions` accepts a policy function,
an injectable `CopilotPermissionPolicy` class, or an instance.

| Decorator | Target | Purpose |
| --- | --- | --- |
| `@UserMessage(template?)` | method | Makes the method a Copilot turn. Also accepts `{ template, stream }`. |
| `@UserMessageFrom(path)` | method | Reads the template from a file at bootstrap (see [prompt templates](#prompt-templates)). |
| `@Message()` | parameter | The user message (or `{{message}}` in a template). |
| `@P(name)` | parameter | Binds a `{{name}}` template placeholder. |
| `@ConversationId()` | parameter | Conversation key; omit for ephemeral turns. |
| `@Signal()` | parameter | An `AbortSignal` for the turn. |
| `@Returns(zodSchema)` | method | [Structured output](#structured-output). |
| `@Tools(...sources)` | class or method | Adds tool sources to the agent or one method. |

Register classes with `CopilotModule.forFeature([SupportAgent], { tools })`; the optional
`tools` are added to every agent of that call. The class becomes an injectable provider whose
stubs are replaced with generated implementations. The method's own name is never sent.

`CopilotAgents` is the registry service:

| Method | Purpose |
| --- | --- |
| `create(Agent, conversationId, { principal? })` | A bound instance that creates the conversation if needed (`create-or-resume`). |
| `bind(Agent, conversationId, { mode?, principal? })` | A bound instance; `resume-only` by default. |
| `delete(Agent or name, conversationId, { principal? })` | Deletes the conversation's session. |
| `respondToPermission(conversationId, requestId, decision)` | Answers an `ask-user` request from a stream. |
| `handle(Agent or name)` / `definition(...)` / `names()` | The core `AgentHandle`, the final definition, and all agent names. |

Bound instances omit the `@ConversationId()` argument:

```ts
await agents.create(SupportAgent, "c1").answer("Hi");
await agents.bind(SupportAgent, "c1").answer("More"); // CopilotConversationNotFoundError if c1 doesn't exist
```

## Prompt templates

Templates use `{{name}}` placeholders. Values are inserted as strings; objects are
JSON-serialized and `null`/`undefined` become empty. Rules are checked at bootstrap:

- Every placeholder must be bound by `@P(name)`, or be `{{message}}` with a `@Message()` parameter.
- Every `@P(name)` must appear in the template, and `@P("message")` conflicts with `@Message()`.
- Without a template, exactly one `@Message()` parameter is required and `@P()` is not allowed.
- A parameter can carry only one Copilot parameter decorator.

```ts
@UserMessage("Translate to {{language}}:\n\n{{message}}")
translate(@P("language") language: string, @Message() text: string): Promise<string> {
  return implementedByCopilot();
}
```

`@UserMessageFrom(path)` loads the template from a file at bootstrap. Relative paths resolve
against the `templatesDir` module option (default `process.cwd()`). Absolute paths and `file:`
URLs are used as is, so a template next to its source file can be loaded with
`@UserMessageFrom(new URL("./prompts/triage.md", import.meta.url))`. In a compiled app, copy the
template files into the build output, or point `templatesDir` at the source folder.

## Return types

| Declared return | Behavior |
| --- | --- |
| `Promise<string>` | Resolves with the final reply text. |
| `Promise<T>` with `@Returns(schema)` | Resolves with the validated structured result. |
| `Observable<CopilotAgentEvent>` | A cold [event stream](streaming.md); unsubscribing aborts the turn. |
| `AsyncIterable<CopilotAgentEvent>` | Set `@UserMessage({ stream: "async-iterable" })`. |

`Observable` is inferred from `design:returntype` metadata, so import it as a value
(`import { Observable } from "rxjs"`), or set `{ stream: "observable" }` explicitly.
`@Returns` cannot be combined with a streaming result.

## Structured output

```ts
const Order = z.object({ id: z.string(), status: z.enum(["shipped", "pending"]) });
const order = await agent.send({ prompt: "Extract the order from: ...", schema: Order });
```

The schema is converted to JSON Schema and sent as the response format; the reply is parsed
and validated. Non-object schemas are wrapped as `{ value }` on the wire and unwrapped for you.
Invalid replies raise `CopilotOutputValidationError` (HTTP `502` in NestJS).

## Functional agents in NestJS

Plain `defineAgent()` definitions can be registered too, and injected as core `AgentHandle`s:

```ts
const triage = defineAgent({ name: "triage", instructions: "Classify the ticket." });

@Module({ imports: [CopilotModule.forFeature([triage])] })
export class TicketsModule {}

@Injectable()
export class TicketsService {
  constructor(@InjectAgent("triage") private readonly triage: AgentHandle) {}
}
```

The injection token is `getAgentToken("triage")`.

## Per-agent configuration overrides

The module option `agents.<name>` overrides decorator values per environment. It accepts
`description`, `instructions`, `model`, `reasoningEffort`, `provider` (name or inline),
`mcpServers` (names or inline), `availableTools`, `excludedTools`, and `builtInTools`.

```ts
CopilotModule.forRoot({
  agents: { support: { model: "claude-sonnet-4.5", provider: "foundry" } },
});
```
