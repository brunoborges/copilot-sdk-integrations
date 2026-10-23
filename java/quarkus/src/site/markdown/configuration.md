---
title: Configuration reference
---

<a id="configuration-reference"></a>

# Configuration reference

**In this chapter**

- [Property format](#property-format)
- [Build-time properties](#build-time-properties)
- [Client properties](#client-properties)
- [Per-agent properties](#per-agent-properties)
- [MCP properties](#mcp-properties)
- [BYOK properties](#byok-properties)
- [Execution properties](#execution-properties)
- [Health properties](#health-properties)
- [Endpoint properties](#endpoint-properties)
- [Security properties](#security-properties)
- [Streaming properties](#streaming-properties)
- [Session properties](#session-properties)
- [Configuration examples and precedence](#configuration-examples-and-precedence)

Configuration uses ordinary Quarkus config sources: `application.properties`,
profiles (`%dev.`, `%test.`, `%prod.`), system properties, environment
variables, and any additional MicroProfile Config source. All keys live under
`quarkus.copilot`. Runtime keys are mapped by
`io.github.brunoborges.copilot.quarkus.runtime.config.CopilotRuntimeConfig`;
build-time switches by `CopilotBuildTimeConfig` in the deployment module.

Only the extension-owned keys below are mapped automatically. Arbitrary
`SessionConfig` fields are not forwarded from similarly named properties.
Use SDK beans/customizers for settings not listed here.

<a id="property-format"></a>

## Property format

| Value | Format |
| --- | --- |
| Duration | `30s`, `250ms`, `2m`, or ISO-8601 such as `PT2M`. A bare number means seconds. |
| List | Comma-separated: `a,b,c`. Escape a literal comma as `\,`. Indexed keys (`name[0]=a`) also work. |
| Empty list | Not expressible: an empty value is treated as unset. Per-agent list keys accept `none` to mean "empty". |
| Map key with dots | Quote the segment: `quarkus.copilot.agents."customer.agent".response-timeout=30s`. |
| Enum | Lower-case values as documented, for example `stdio`, `http`, `openai`, `responses`. |
| Secret | Reference an environment variable or secret-backed source: `${FOUNDRY_API_KEY}`. |

Spring Boot's `copilot.*` keys map to `quarkus.copilot.*` with the same names,
except where this page notes a difference.

<a id="build-time-properties"></a>

## Build-time properties

These are fixed when the application is built; changing them requires a
rebuild (dev mode re-augments automatically). The binding type is
`io.github.brunoborges.copilot.quarkus.deployment.CopilotBuildTimeConfig`.

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `quarkus.copilot.enabled` | Boolean | `true` | Register the client, agent discovery, agent beans, and all optional integrations. When `false`, nothing from the extension is registered. |
| `quarkus.copilot.health.enabled` | Boolean | `true` | Register the `copilot` readiness check when SmallRye Health is present. |
| `quarkus.copilot.metrics.enabled` | Boolean | `true` | Register the Micrometer telemetry when Quarkus Micrometer is present. Does not disable an application-provided `CopilotAgentTelemetry` bean. |
| `quarkus.copilot.info.enabled` | Boolean | `true` | Contribute a `copilot` section to `/q/info` when `quarkus-info` is present. |
| `quarkus.copilot.endpoint.enabled` | Boolean | `false` | Serve the read-only diagnostics endpoint at `/q/copilot` and `/q/copilot/{agents,execution,models,sessions}`. The build fails if `quarkus-vertx-http` is missing. |
| `quarkus.copilot.security.identity-token-resolver.enabled` | Boolean | `false` | Register `IdentityCopilotTokenResolver`, authenticating agents as the current `SecurityIdentity` using its bearer token. The build fails if no Quarkus Security extension is present. |
| `quarkus.copilot.test-runtime.enabled` | Boolean | `true` | Let `copilot-quarkus-test`, when on the class path, replace the client with the scripted test runtime. See [Testing](testing.html). |

The Mutiny return-type adapter has no switch; it is registered whenever Mutiny
is on the class path. See [Mutiny streaming](streaming.html).

<a id="client-properties"></a>

## Client properties

These configure the extension-produced client, not individual conversations.

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `quarkus.copilot.eager-start` | Boolean | `false` | Connect during application startup instead of first use. |
| `quarkus.copilot.startup-timeout` | Duration | `30s` | Positive eager connection wait; must fit signed 64-bit nanoseconds. Does not bound subsequent cleanup. |
| `quarkus.copilot.cli-path` | String | Unset | Explicit local executable; non-blank if supplied. SDK resolves the runtime when omitted. |
| `quarkus.copilot.cli-args` | List of strings | Empty | Additional local runtime arguments; blank elements are rejected. |
| `quarkus.copilot.working-directory` | String | Unset | Local process working directory; omitted values retain SDK defaults. Not a per-session working directory. |
| `quarkus.copilot.log-level` | String | SDK default, `info` | Non-blank, forwarded to SDK/CLI without an extension-specific level enum. |
| `quarkus.copilot.cli-url` | String | Unset | Existing raw-TCP server address; switches the client away from stdio. |
| `quarkus.copilot.tcp-connection-token` | String | Unset | Non-blank external-server token; requires `cli-url`. |
| `quarkus.copilot.connection` | `stdio`, `inprocess` | Unset | Runtime transport; `inprocess` (experimental) hosts the runtime in the JVM. Unset keeps the SDK default. |

`cli-url` cannot be combined with `cli-path`, non-empty `cli-args`, or
`working-directory`. Supported address forms include `4321`, `localhost:4321`,
`http://localhost:4321`, and `[::1]:4321`. Ports must be 1-65535. URI credentials,
paths (including a trailing `/`), queries, and fragments are rejected.
Validation errors name the property but never echo the configured value.

**SDK 1.0.24 uses raw TCP for external-server connections. Neither `http://`
nor `https://` enables HTTP or TLS.** Use a trusted local connection or a
separately secured tunnel, especially when sending a connection token. The
external server owns its GitHub authentication and process lifecycle.

The client and its `CopilotClientOptions` are `@DefaultBean`s. An application
that produces its own `CopilotClientOptions` or `CopilotClient` bean replaces
them, and these client properties are then not applied (`eager-start` and
`startup-timeout` still are). In tests, `CopilotContainerTestResource` sets
`cli-url` and `tcp-connection-token` for you; see [Testing](testing.html).

### In-process runtime (experimental)

Set `quarkus.copilot.connection=inprocess` to host the Copilot runtime inside the JVM
instead of starting a CLI child process. Add JNA and the native runtime for
each platform you deploy to:

```xml
<dependency>
  <groupId>net.java.dev.jna</groupId>
  <artifactId>jna</artifactId>
  <version>5.19.1</version>
</dependency>
<dependency>
  <groupId>com.github</groupId>
  <artifactId>copilot-sdk-java-runtime</artifactId>
  <version>${copilot-sdk.version}</version>
  <classifier>darwin-arm64</classifier>
</dependency>
```

Classifiers are `linux-x64`, `linux-arm64`, `linuxmusl-x64`, `linuxmusl-arm64`,
`win32-x64`, `win32-arm64`, `darwin-x64`, and `darwin-arm64`. A `runtime.node`
next to the CLI named by `COPILOT_CLI_PATH`, or next to `copilot` on `PATH`,
also works. Startup fails with the missing dependency and the classifier for the
current platform when JNA or the runtime cannot be found.

In-process mode cannot be combined with `cli-path`, `cli-args`,
`working-directory`, `cli-url`, or `tcp-connection-token`; startup names every
conflicting setting. A JVM loads only one native runtime library.
On JDK 24 and later, add `--enable-native-access=ALL-UNNAMED` to avoid native
access warnings. `quarkus.copilot.connection=stdio` pins the child-process transport even
when `COPILOT_SDK_DEFAULT_CONNECTION=inprocess` is set.

In-process mode is for JVM mode; native images are untested.

There is no extension property for a GitHub credential. Local authentication
follows SDK/CLI rules; external secrets should use supported environment or
config-source mechanisms, never committed literals.

<a id="per-agent-properties"></a>

## Per-agent properties

Replace `<name>` with the logical `@CopilotAgent` name: the `name` attribute,
or, when it is omitted, the interface's simple name with the first letter
lower-cased (`SupportAgent` becomes `supportAgent`). Keys are matched exactly. The values are converted
into `CopilotAgentsProperties.Agent` records in the `runtime.config` package.

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `quarkus.copilot.agents.<name>.model` | String | Annotation value, then runtime default | Override of `model`. |
| `quarkus.copilot.agents.<name>.reasoning-effort` | String | Annotation value, then runtime default | Override of `reasoningEffort`; support depends on the selected model. |
| `quarkus.copilot.agents.<name>.message-source` | String | Annotation value, then runtime default (`user`) | `user`, `system`, or `agent-<id>` provenance label for prompts; `none` clears the annotation value. Invalid values fail startup. Not an authenticated identity; see [Message source](agents.html#message-source). |
| `quarkus.copilot.agents.<name>.response-timeout` | Duration | `60s` | At least `1ms`, representable in signed 64-bit nanoseconds. Bounds SDK response waiting; synchronous agent methods also wait at most this long in total. |
| `quarkus.copilot.agents.<name>.mcp-servers` | List of strings | Annotation selection | Replace the agent's selected property server / SDK bean names. Omitted retains annotation; `none` selects none. Duplicate, missing, or ambiguous references fail initialization. |
| `quarkus.copilot.agents.<name>.provider` | String | Annotation selection | Replace the agent's BYOK provider (property or `@Named` SDK `ProviderConfig` bean name). Omitted retains annotation; `none` selects none. Missing or ambiguous references, or a provider without a model, fail initialization. |
| `quarkus.copilot.agents.<name>.available-tools` | List of strings | Annotation `availableTools` | SDK tool allow-list (`name`, `builtin:`, `custom:`, `mcp:` patterns). `none` clears the annotation value. Must include `custom:`/`mcp:` entries when the agent declares tools/MCP servers; never widened. |
| `quarkus.copilot.agents.<name>.excluded-tools` | List of strings | Annotation `excludedTools` | SDK tool deny-list; `none` clears it. Combining it with `available-tools` fails initialization. |
| `quarkus.copilot.agents.<name>.built-in-tools` | Boolean | Annotation `builtInTools` (`true`) | `false` excludes `builtin:*`; cannot be combined with `available-tools`. |
| `quarkus.copilot.agents.<name>.tool-search.enabled` | Boolean | Annotation `toolSearch` (runtime default) | Whether MCP and external tool definitions are deferred behind the runtime's tool-search tool. |
| `quarkus.copilot.agents.<name>.tool-search.defer-threshold` | Integer | Annotation `toolSearchDeferThreshold` (runtime default, 30) | Total tool count at which tools are deferred; at least 1; cannot be combined with `tool-search.enabled=false`. |
| `quarkus.copilot.agents.<name>.excluded-built-in-agents` | List of strings | Annotation value | Runtime built-in sub-agents to hide; `none` clears it. |
| `quarkus.copilot.agents.<name>.working-directory` | String | Annotation value, then runtime default | Session working directory for runtime tools. An empty value is unset, so it cannot restore the runtime default over an annotation value. |
| `quarkus.copilot.agents.<name>.additional-directories` | List of strings | Annotation value | Extra directories the session may access; `none` clears it. |
| `quarkus.copilot.agents.<name>.permissions.allow` | List of strings | Empty | Permission request kinds to approve (`shell`, `write`, `read`, `mcp`, `custom-tool`, `url`, `memory`, `hook`). |
| `quarkus.copilot.agents.<name>.permissions.allow-tools` | List of strings | Empty | Custom/MCP tool names to approve. |
| `quarkus.copilot.agents.<name>.permissions.allow-mcp-servers` | List of strings | Empty | MCP server names whose tool calls are approved. |
| `quarkus.copilot.agents.<name>.permissions.allow-agent-tools` | Boolean | `false` | Approve the agent's own declared custom tools. |
| `quarkus.copilot.agents.<name>.permissions.allow-read-only` | Boolean | `false` | Approve `read` requests and read-only MCP calls. |

Response waiting excludes queueing, connection/session metadata lookup,
creation/resumption, and cleanup. It is not a total request deadline.
Instructions and all bean references (`*Ref`/`*Refs` attributes, including hook
beans) remain annotation/bean configuration. `permissions.*` builds a
policy only when no `permissionHandlerRef` bean is selected (combining both fails
initialization); with neither, every permission request is rejected. See
[Tool governance, permissions, and hooks](governance.html).

Every discovered agent is validated during startup, so a bad override fails
the application rather than the first request. Explicit lower-case,
kebab-case agent names make property configuration easiest. Quote names
containing dots:

```ini
quarkus.copilot.agents."customer.agent".response-timeout=30s
```

<a id="mcp-properties"></a>

## MCP properties

`quarkus.copilot.mcp.servers` is an empty-by-default map, converted into
`CopilotMcpProperties.Server` records. Names must match
`[A-Za-z][A-Za-z0-9._-]*`. Definitions are validated at startup, when agents are
initialized, even if not selected; only explicitly selected servers are supplied to an agent.

Replace `<server>` with a server name:

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `quarkus.copilot.mcp.servers.<server>.type` | `stdio` or `http` | `stdio` | Native SDK transport: local subprocess or remote HTTP/SSE. |
| `quarkus.copilot.mcp.servers.<server>.command` | String | Unset | Required executable for stdio. Not a shell command line. Forbidden for HTTP. |
| `quarkus.copilot.mcp.servers.<server>.args` | List of strings | Empty | Literal process arguments. Forbidden for HTTP. |
| `quarkus.copilot.mcp.servers.<server>.env.<VAR>` | Map of strings | Empty | Additional process environment values. Forbidden for HTTP. |
| `quarkus.copilot.mcp.servers.<server>.working-directory` | String | Unset | Optional stdio process directory. Forbidden for HTTP. |
| `quarkus.copilot.mcp.servers.<server>.url` | String | Unset | Required for HTTP: absolute HTTP(S) URL with a host, no credentials/fragment, and optional port 1-65535. Forbidden for stdio. |
| `quarkus.copilot.mcp.servers.<server>.headers.<Header>` | Map of strings | Empty | HTTP request headers. Forbidden for stdio. |
| `quarkus.copilot.mcp.servers.<server>.tools` | List of strings | Empty (no tools) | Explicit server tool allowlist. `*` enables all server tools. |
| `quarkus.copilot.mcp.servers.<server>.timeout` | Duration | SDK/runtime default | Server tool-call timeout; at least `1ms`, at most `2147483647ms`. Forwarded as integer milliseconds, truncating sub-millisecond precision. Independent of agent response timeout. |

See [MCP servers](mcp.html) for property, annotation, native bean, and raw SDK
examples, lifecycle, and security boundaries. Unknown transport values and
mixed local/remote settings fail startup. Server definitions do not grant tool
permissions or automatically disable runtime-provided servers.

<a id="byok-properties"></a>

## BYOK properties

`quarkus.copilot.providers` is an empty-by-default map, converted into
`CopilotProviderProperties.Provider` records. Names must match
`[A-Za-z][A-Za-z0-9._-]*`. Definitions are validated at startup, when agents are
initialized, even if not selected; only an explicitly selected provider is supplied to an agent.

Replace `<provider>` with a provider name:

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `quarkus.copilot.providers.<provider>.type` | `openai`, `azure`, or `anthropic` | `openai` | SDK provider type. Use `openai` for OpenAI-compatible endpoints, including Ollama, Foundry Local, and Foundry `/openai/v1/` URLs. |
| `quarkus.copilot.providers.<provider>.base-url` | String | None (required) | Absolute HTTP(S) URL with a host, no credentials/fragment, and optional port 1-65535. |
| `quarkus.copilot.providers.<provider>.api-key` | String | Unset | Static API key. Optional for local providers. |
| `quarkus.copilot.providers.<provider>.api-key-command` | String | Unset | Command the runtime runs before each provider request to obtain an API key. |
| `quarkus.copilot.providers.<provider>.bearer-token` | String | Unset | Static bearer token sent as the `Authorization` header; not refreshed. |
| `quarkus.copilot.providers.<provider>.wire-api` | `completions` or `responses` | SDK default (`completions`) | OpenAI wire format. Forbidden for `anthropic`. |
| `quarkus.copilot.providers.<provider>.azure-api-version` | String | Unset (versionless `v1` route) | Azure API version. Only allowed for `azure`. Spring Boot's equivalent is `azure.api-version`. |
| `quarkus.copilot.providers.<provider>.headers.<Header>` | Map of strings | Empty | Additional request headers. |
| `quarkus.copilot.providers.<provider>.max-prompt-tokens` | Integer | SDK/runtime default | Positive prompt token limit. |
| `quarkus.copilot.providers.<provider>.max-output-tokens` | Integer | SDK/runtime default | Positive output token limit. |

`api-key`, `api-key-command`, and `bearer-token` are mutually exclusive. See
[BYOK providers](byok.html) for agent selection, native `ProviderConfig` beans,
and security notes.

<a id="execution-properties"></a>

## Execution properties

These limits are shared by all agents in one application. They are converted
into `CopilotAgentExecutionProperties`.

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `quarkus.copilot.execution.max-concurrent-invocations` | Integer | `8` | Positive maximum of invocations performing SDK work, including response waiting and cleanup. |
| `quarkus.copilot.execution.max-outstanding-invocations` | Integer | `64` | Running plus queued/serialized accepted calls; must be at least the concurrency limit. |

Admission does not block the caller. A full envelope returns a
`RejectedExecutionException` without contacting the SDK. Outstanding counts
include calls serialized behind another call to the same conversation, not
just tasks waiting for a global slot. These are client invocation bounds, not
a guarantee about detached work continuing in the runtime.

<a id="health-properties"></a>

## Health properties

These apply only when the readiness check is registered (SmallRye Health present
and `quarkus.copilot.health.enabled=true`). See
[Health, info, and endpoint](management.html).

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `quarkus.copilot.health.timeout` | Duration | `5s` | Positive budget for one health check, including an optional client start and the status/auth calls. Exceeding it reports `DOWN`. |
| `quarkus.copilot.health.start-client` | Boolean | `false` | Let health checks start a lazily configured client that is not connected. When `false`, such a client reports `UP` with its state and a reason instead of being started. |
| `quarkus.copilot.health.require-authentication` | Boolean | `true` | Report `DOWN` when the connected runtime is not authenticated. Set `false` for BYOK-only applications. |

<a id="endpoint-properties"></a>

## Endpoint properties

These apply only when `quarkus.copilot.endpoint.enabled=true` at build time.

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `quarkus.copilot.endpoint.timeout` | Duration | `5s` | Positive budget for each runtime-backed endpoint operation (`models`, `sessions`). |
| `quarkus.copilot.endpoint.start-client` | Boolean | `false` | Let the endpoint start a lazily configured client that is not connected. |
| `quarkus.copilot.endpoint.show-instructions` | Boolean | `false` | Include agent system instruction content in the `agents` view. |
| `quarkus.copilot.endpoint.include-all-sessions` | Boolean | `false` | List all runtime sessions instead of only extension-created (`agent-`) sessions. |
| `quarkus.copilot.endpoint.show-session-summaries` | Boolean | `false` | Include runtime-generated session summaries, which may contain prompt or business content. |

The endpoint is served under the Quarkus non-application root path (`/q` by
default). Protect it with Quarkus HTTP security policies or the management
interface as you would any other diagnostic route.

<a id="security-properties"></a>

## Security properties

Applies only when `quarkus.copilot.security.identity-token-resolver.enabled=true`.
See [Per-user authentication](authentication.html#quarkus-security-identity).

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `quarkus.copilot.security.identity.ambient-fallback` | Boolean | `false` | Fall back to ambient client authentication instead of failing when there is no authenticated identity or bearer token. |

<a id="streaming-properties"></a>

## Streaming properties

These apply only when Mutiny is on the class path.

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `quarkus.copilot.streaming.buffer-capacity` | Integer | `256` | Positive maximum events buffered per `Multi` subscriber; a subscriber falling further behind fails with `CopilotAgentStreamOverflowException`. |

This is not a bound on buffers introduced by downstream Mutiny operators.
Spring Boot's equivalent is `copilot.reactor.buffer-capacity`.

<a id="session-properties"></a>

## Session properties

These configure the injectable `CopilotSessionStore` and age-based session
cleanup. See [Conversations and lifecycle](conversations.html#managing-sessions).

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `quarkus.copilot.sessions.timeout` | Duration | `2m` | Positive maximum wait for each `CopilotSessionStore` or cleanup call, including how long a deletion waits for a turn in flight on the session. |
| `quarkus.copilot.sessions.cleanup.max-age` | Duration | unset | Positive age after which agent sessions not modified are deleted. Unset disables cleanup. |
| `quarkus.copilot.sessions.cleanup.interval` | Duration | half of `max-age`, clamped to `1m`–`1h` | Positive delay between cleanup sweeps. |

Cleanup runs on a dedicated daemon thread only while the application runs. It
only deletes local sessions with the extension's `agent-` prefix, skips a sweep
while the client is not connected (it never starts the client), skips sessions
with a turn queued or running, and re-reads each session's metadata just before
deleting it. Every replica sharing a runtime may run cleanup; deleting an
already-deleted session is harmless.

<a id="configuration-examples-and-precedence"></a>

## Configuration examples and precedence

Example local configuration:

```ini
quarkus.copilot.cli-path=/absolute/path/to/copilot
quarkus.copilot.eager-start=false
quarkus.copilot.startup-timeout=30s

quarkus.copilot.agents.quarkus-advisor.response-timeout=60s
# quarkus.copilot.agents.quarkus-advisor.model=an-available-model-id
# quarkus.copilot.agents.quarkus-advisor.reasoning-effort=high

quarkus.copilot.execution.max-concurrent-invocations=8
quarkus.copilot.execution.max-outstanding-invocations=64

quarkus.copilot.health.timeout=5s
quarkus.copilot.streaming.buffer-capacity=256

# Build time
quarkus.copilot.metrics.enabled=true
quarkus.copilot.endpoint.enabled=true
```

Alternative external-server configuration; do not add the local-only settings:

```ini
quarkus.copilot.cli-url=localhost:4321
quarkus.copilot.tcp-connection-token=${COPILOT_SERVER_TOKEN}
```

Omit the token property if the server does not require one. The placeholder is
an application/environment secret, not a new SDK GitHub authentication property.

Precedence is:

1. Quarkus resolves config sources using its standard ordinals (system
   properties over environment variables over `application.properties`), with
   the active profile's `%profile.` keys winning over unprefixed keys.
2. Per-agent `model`/`reasoning-effort`, `mcp-servers`, `provider`, and tool
   governance properties override annotation values.
3. Omitted model/reasoning settings leave those choices to SDK/runtime defaults.
4. Customizers run after declarative session settings, subject to the managed
   ID/permission/streaming invariants described in
   [Events and customizers](events.html#session-customizers).

For example, `-Dquarkus.copilot.agents.quarkus-advisor.response-timeout=30s`
overrides the value in `application.properties`. Producing your own client or
options bean changes ownership/mapping as described in
[Client properties](#client-properties).

---

**Previous:** [Tool governance, permissions, and hooks](governance.html)

**Next:** [Mutiny streaming](streaming.html)
