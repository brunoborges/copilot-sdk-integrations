# Configuration and headers

## Component options

Configure the `CopilotComponent` before the Camel context starts, using its setters
or your Camel runtime's component configuration. Registry references point to
application-managed beans.

| Option | Meaning |
| --- | --- |
| `client` | `CopilotClient` to use. Defaults to the single registry bean, otherwise a component-owned client. |
| `telemetry` | `CopilotAgentTelemetry`. Defaults to the single registry bean, otherwise none. |
| `agentSettings` | `CopilotAgentSettings`. Defaults to the single registry bean, otherwise defaults. |
| `agentTypes` | Comma-separated fully qualified `@CopilotAgent` interface names. |
| `maxConcurrentInvocations` | Concurrent execution limit shared by agents. |
| `maxOutstandingInvocations` | Outstanding invocation limit shared by agents. |
| `sessionTimeout` | Maximum wait per session store or cleanup call; default `2m`. |
| `sessionMaxAge` | Expire sessions not modified within this duration; unset by default. |
| `sessionCleanupInterval` | Cleanup delay; defaults to half of `sessionMaxAge`, bounded between `1m` and `1h`. |

## Agent endpoint options

These settings configure `copilot:agent:name`. Lists are comma separated. `*Ref`
and `*Refs` options resolve named beans in the Camel registry.

| Options | Purpose |
| --- | --- |
| `prompt`, `conversationId` | Camel Simple expressions; default to the body and `CopilotConversationId` header respectively. |
| `outputClass` | Fully qualified class for structured results. |
| `instructions`, `instructionsResource` | Inline or resource-backed system instructions. |
| `instructionsMode`, `systemMessageRef` | Instruction mode and a registry system-message configuration. |
| `model`, `reasoningEffort` | Model and reasoning settings. |
| `toolRefs` | Registry application tools selected for the agent. |
| `mcpServers`, `provider` | Shared agent engine's MCP server and BYOK provider selectors. |
| `permissionHandlerRef` | Registry permission handler, including a `copilot:permission` route. |
| `listenerRefs`, `customizerRefs`, `hookRefs` | Registry session listeners, customizers, and hooks. |
| `tokenResolverRef` | Registry token resolver for per-user authentication. |
| `availableTools`, `excludedTools`, `excludedBuiltInAgents` | Tool and built-in agent selection. |
| `builtInTools` | Enable built-in CLI tools; off by default for endpoint agents unless `availableTools` is supplied. |
| `toolSearch`, `toolSearchDeferThreshold` | Runtime tool-search settings. |
| `workingDirectory`, `additionalDirectories` | Runtime working directory and additional accessible directories. |
| `messageSource` | Prompt provenance: `user`, `system`, or `agent-<id>`. |
| `responseTimeout` | Response duration, for example `PT2M`. |
| `allow`, `allowTools`, `allowMcpServers`, `allowAgentTools`, `allowReadOnly` | Explicit permission-policy shortcuts. |

For declarative agents registered through `agentTypes`, configure agent-definition
options on the annotated interface instead. Routing options (`prompt`,
`conversationId`, and `outputClass`) still apply at the endpoint.

Prefer Camel `{{property}}` placeholders for environment-specific choices and
`instructionsResource=classpath:prompts/support.md` for large prompts.

## Consumer endpoint options

| Endpoint | Options |
| --- | --- |
| `copilot:tool:name` | `description`, `parametersClass`, `parametersSchema`, `skipPermission` |
| `copilot:permission:name` | `timeout`, for example `PT5M` |
| `copilot:events:name` | `types` (comma-separated filters), `queueSize` (default `1000`) |

See the [tool](tools.html) and [permission/event](permissions-events.html)
guides for the exchange body contracts.

## Exchange headers

Use constants from `com.microsoft.copilot.integrations.camel.CopilotConstants`
instead of string literals where possible.

| Header | Used on |
| --- | --- |
| `CopilotConversationId` | Agent input/reply and events. |
| `CopilotAgentName` | Agent replies and events. |
| `CopilotSessionId` | Tool, permission, and event exchanges. |
| `CopilotToolName` | Tool exchanges. |
| `CopilotToolCallId` | Tool and permission exchanges. |
| `CopilotPermissionKind` | Permission exchanges. |
| `CopilotEventType` | Event exchanges. |

Header values are context, not proof of identity. Enforce authentication and
authorization in application routes and services.
