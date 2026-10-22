---
title: Configuration
---

# Configuration

Core is configured with `createCopilot(options)`; NestJS with `CopilotModule.forRoot(options)`
or `forRootAsync(...)`, which accept the same `CopilotOptions` plus NestJS-specific settings.

**In this chapter**

- [`CopilotOptions`](#copilotoptions)
- [Runtime connection and authentication](#runtime-connection-and-authentication)
- [NestJS module options](#nestjs-module-options)
- [Async configuration](#async-configuration)
- [Validation](#validation)
- [Injection tokens](#injection-tokens)

## `CopilotOptions`

| Option | Default | Description |
| --- | --- | --- |
| `cliPath` | `COPILOT_CLI_PATH`, then the SDK's bundled runtime | Path to the Copilot runtime to spawn. |
| `cliUrl` | none | Connect to an existing runtime server instead of spawning one. Exclusive with `cliPath`. |
| `startup` | `"lazy"` | `lazy` starts the runtime on the first turn. `eager`: NestJS starts it at bootstrap, and bootstrap fails if it can't start; in core, call `copilot.start()` yourself. |
| `githubToken` | none | Service GitHub token. With `cliUrl`, a per-session fallback token; see [token precedence](context-and-identity.md#token-precedence). |
| `useLoggedInUser` | `true` when no token is configured | Use the user logged in to the Copilot CLI. Setting it together with `cliUrl` is an error: an external runtime manages its own authentication. |
| `baseDirectory` | `COPILOT_HOME`, then `~/.copilot` | Runtime state directory. |
| `logLevel` | runtime default | Runtime log level: `none`, `error`, `warning`, `info`, `debug`, or `all`. |
| `limits.maxConcurrentTurns` | `16` | Turns running at once across all agents. |
| `limits.maxQueuedTurns` | `64` | Turns waiting for a slot before `CopilotAdmissionRejectedError`. |
| `limits.conversationWaitMs` | `30000` | How long a turn waits for a busy conversation. |
| `responseTimeout` | `300000` | Per-turn timeout in ms, including queueing. Overridden by the `timeout` send option. |
| `approvalWindow` | `120000` | How long an `ask-user` permission waits for an answer before it is denied. |
| `store` | none | [`SessionStore`](conversations.md#session-stores-and-multiple-replicas) for cross-replica conversation leases. |
| `hooks` | none | Global [hooks](governance.md#hooks), run before each agent's hooks. |
| `onEvent` | none | Global [event listener](streaming.md#global-event-listeners) `(event, context)`. |
| `tokenResolver` | none | Per-user [GitHub token](context-and-identity.md#per-user-github-tokens) resolver. |
| `configureClient` | none | `(options) => options`: adjust the raw SDK `CopilotClientOptions`. |
| `clientFactory` | SDK `CopilotClient` | Replace the SDK client (the test runtime uses this). |
| `logger` | console, `[copilot]` prefix, debug dropped | A `CopilotLogger` (`debug`, `info`, `warn`, `error`). NestJS uses its `Logger`. |

The SDK client always runs in its `empty` mode: no default tools or instructions leak in;
each agent declares what it uses.

## Runtime connection and authentication

| Setup | Options |
| --- | --- |
| Local development | Nothing: the bundled runtime runs as the user logged in to the Copilot CLI. |
| Service account | `githubToken: process.env.COPILOT_GITHUB_TOKEN` (a GitHub token whose user has Copilot access). |
| Per-user billing | A `tokenResolver` or `CopilotTokenResolver`; see [context and identity](context-and-identity.md). |
| Shared runtime server | `cliUrl: "localhost:4321"`, for several replicas that resume each other's conversations. |
| BYOK | A `provider` per agent, or named `providers` in NestJS; see [BYOK providers](agents.md#byok-providers). |

## NestJS module options

| Option | Default | Description |
| --- | --- | --- |
| `providers` | none | Named BYOK providers, selected per agent with `provider: "name"`. |
| `mcpServers` | none | Named MCP servers, selected per agent with `mcpServers: ["name"]`. |
| `agents` | none | [Per-agent overrides](agents.md#per-agent-configuration-overrides) keyed by agent name. |
| `shutdownGracePeriod` | `30000` | How long shutdown waits for in-flight turns before aborting them. |
| `context` | `{}` | [Request context](context-and-identity.md#the-nestjs-context-interceptor) capture: `principal`, `headers`, `conversationId`, `attributes`, `abortOnDisconnect`. `false` disables the interceptor. |
| `templatesDir` | `process.cwd()` | Base directory for relative `@UserMessageFrom()` paths. |
| `eventEmitter` | `false` | Re-emit events on `@nestjs/event-emitter` as `copilot.<type>`; `{ prefix }` changes the prefix. See [global event listeners](streaming.md#global-event-listeners). |
| `exceptionFilter` | `true` | Register `CopilotExceptionFilter` globally; see [HTTP error mapping](operations.md#http-error-mapping). |
| `copilotFactory` | none | Advanced: create the core `Copilot` yourself (the testing module uses this). |
| `isGlobal` | `true` | Register the module globally. |

`CopilotModule.forFeature(agents, { tools })` registers agent classes and functional
definitions; `tools` lists tool sources added to every agent of that feature.

## Async configuration

```ts
CopilotModule.forRootAsync({
  imports: [ConfigModule],
  inject: [ConfigService],
  useFactory: (config: ConfigService) => ({
    githubToken: config.get("COPILOT_GITHUB_TOKEN"),
    limits: { maxConcurrentTurns: config.get("COPILOT_MAX_TURNS", 16) },
    providers: {
      azure: { type: "azure", baseUrl: config.getOrThrow("AZURE_OPENAI_URL"), apiKey: config.get("AZURE_OPENAI_KEY") },
    },
  }),
});
```

`useClass` and `useExisting` (a class implementing `create()`) also work, as with any Nest
configurable module.

## Validation

NestJS validates the options at bootstrap with a strict schema. Unknown keys, wrong types,
`cliUrl` together with `cliPath`, and `availableTools` together with `excludedTools` in an
agent override fail fast with a `CopilotConfigurationError`:

```text
Invalid CopilotModule options: cliUrl: cliUrl and cliPath are mutually exclusive
```

`validateModuleOptions(options)` runs the same validation. Core checks its own invariants
when an agent is created or the runtime starts (for example, `useLoggedInUser` with `cliUrl`,
or both tool filters on one agent).

## Injection tokens

| Token | Value |
| --- | --- |
| `COPILOT` (`@InjectCopilot()`) | The core `Copilot` instance. |
| `COPILOT_CLIENT` (`@InjectCopilotClient()`) | `() => Promise<CopilotClient>`: the raw SDK client, started on demand. |
| `COPILOT_CLIENT_CUSTOMIZER` | A function (or array) adjusting the SDK client options. |
| `COPILOT_SESSION_CUSTOMIZER` | A function (or array) adjusting each session config. |
| `COPILOT_PERMISSION_POLICY` | The default permission policy for agents that declare none. |
| `COPILOT_CONTEXT_BRIDGE` | A [context bridge](context-and-identity.md#nestjs-cls-and-other-context-stores) (or array). |
| `COPILOT_MODULE_OPTIONS` | The validated module options. |
| `getAgentToken(name)` (`@InjectAgent(name)`) | A functional agent's `AgentHandle`. |
