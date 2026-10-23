---
title: Actuator health, info, and endpoint
---

<a id="actuator"></a>

# Actuator health, info, and endpoint

**In this chapter**

- [Activation](#activation)
- [Health indicator](#health-indicator)
- [Copilot endpoint](#copilot-endpoint)
- [Info contribution](#info-contribution)
- [Security and privacy](#security-and-privacy)

<a id="activation"></a>

## Activation

Add `org.springframework.boot:spring-boot-starter-actuator`. Each contribution
is an independent auto-configuration that activates only when the relevant
actuator classes are present, `copilot.enabled` is not `false`, and a
`CopilotClient` bean exists (starter-managed or application-provided).

| Contribution | Type | Enabled by default | Disable or tune with |
| --- | --- | --- | --- |
| `copilot` health indicator | `com.microsoft.copilot.integrations.springboot.actuate.CopilotHealthIndicator` | Yes | `management.health.copilot.enabled=false`, [`copilot.health.*`](configuration.html#actuator-properties) |
| `copilot` endpoint | `com.microsoft.copilot.integrations.springboot.actuate.CopilotEndpoint` | Only when exposed | `management.endpoints.web.exposure.include`, `management.endpoint.copilot.access`, [`copilot.endpoint.*`](configuration.html#actuator-properties) |
| `copilot` info section | `com.microsoft.copilot.integrations.springboot.actuate.CopilotInfoContributor` | Yes | `management.info.copilot.enabled=false` |

Define a bean named `copilotHealthIndicator`, or a bean of type
`CopilotEndpoint` or `CopilotInfoContributor`, to replace a contribution.

**Lazy clients are never started by diagnostics.** The SDK starts a
disconnected client on the first runtime call, so health checks and endpoint
operations inspect `CopilotClient.getState()` first and only call the runtime
when the client is already `CONNECTED`. Set `copilot.health.start-client=true`
or `copilot.endpoint.start-client=true` to let them connect a lazy client, or
use `copilot.eager-start=true` to connect during startup.

<a id="health-indicator"></a>

## Health indicator

The indicator is registered as `copilot` and contributes to `/actuator/health`
(and therefore readiness/liveness groups only if you add it to them).

| Client state | Status | Runtime calls |
| --- | --- | --- |
| `DISCONNECTED` (lazy, not started yet) | `UNKNOWN` with a `reason` | None, unless `copilot.health.start-client=true` |
| `CONNECTING` | `UNKNOWN` with a `reason` | None, unless `copilot.health.start-client=true` |
| `ERROR` | `DOWN` | None |
| `CONNECTED`, authenticated | `UP` | `getStatus()` and `getAuthStatus()` |
| `CONNECTED`, not authenticated | `DOWN` (or `UP` when `copilot.health.require-authentication=false`) | `getStatus()` and `getAuthStatus()` |
| Runtime error or no response within `copilot.health.timeout` | `DOWN` with `reason` and `error` type | — |

The `copilot.health.timeout` budget (default `5s`) covers starting the client
(when enabled) and both status calls. Set `require-authentication=false` for
applications that use only [BYOK providers](byok.html), which do not require
a GitHub login.

Example details (shown according to `management.endpoint.health.show-details`):

```json
{
  "status": "UP",
  "details": {
    "state": "CONNECTED",
    "version": "1.2.3",
    "protocolVersion": 3,
    "authenticated": true,
    "authType": "user",
    "host": "https://github.com",
    "login": "octocat"
  }
}
```

<a id="copilot-endpoint"></a>

## Copilot endpoint

`@Endpoint(id = "copilot")` has read-only operations and defaults to
`READ_ONLY` access. Like most actuator endpoints, it is **not exposed over HTTP
by default**; following standard actuator rules, the bean is created only when
the endpoint is exposed, for example:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,copilot
```

| Operation | Path | Contacts the runtime |
| --- | --- | --- |
| Overview: client state, agents, and execution capacity | `GET /actuator/copilot` | No |
| Registered agents | `GET /actuator/copilot/agents` | No |
| In-flight/queued invocations and limits | `GET /actuator/copilot/execution` | No |
| Available models | `GET /actuator/copilot/models` | Only when connected (or `copilot.endpoint.start-client=true`) |
| Runtime sessions | `GET /actuator/copilot/sessions` | Only when connected (or `copilot.endpoint.start-client=true`) |

Each agent entry contains its logical `name`, interface `type`, whether it is a
`bound` (ID-less) definition, effective `model` and `reasoningEffort`,
`responseTimeout`, provider `name` and `type`, in-process `tools` names, selected
`mcpServers` names, and the `instructionsMode`. Instruction content is omitted
unless `copilot.endpoint.show-instructions=true`.

`execution` reports `running` (holding one of
`copilot.execution.max-concurrent-invocations` permits), `queued` (accepted
but waiting for a permit or for an earlier call to the same conversation), and
both configured limits. It is omitted when the agent invoker is not configured.

`models` and `sessions` return `{"state", "reason", ...}`. When the client is not
connected, or the runtime fails or exceeds `copilot.endpoint.timeout` (default
`5s`), the list is `null` and `reason` explains why; the operation itself
does not fail. Models list `id`, `name`, `policyState`,
`supportedReasoningEfforts`, and `defaultReasoningEffort`. Sessions list
`sessionId`, `summary`, `startTime`, `modifiedTime`, `remote`, and `managed`.
By default only sessions created by this starter (`agent-` prefix) are listed;
set `copilot.endpoint.include-all-sessions=true` to include all runtime sessions.
`summary` is `null` unless `copilot.endpoint.show-session-summaries=true`,
because runtime-generated summaries can contain prompt or business content.

<a id="info-contribution"></a>

## Info contribution

`/actuator/info` gains a `copilot` section that never contacts the runtime:

```json
{
  "copilot": {
    "starter": { "version": "0.1.0-SNAPSHOT" },
    "sdk": { "version": "1.0.24" },
    "connection": { "mode": "stdio" }
  }
}
```

`connection.mode` is derived from the `CopilotClientOptions` bean: `stdio`,
`tcp`, `external-server` (`copilot.cli-url`), `in-process`, `custom`, or
`unknown` when an application supplies its own client without an options bean.
Addresses and tokens are not reported. Versions fall back to `unknown` when the
jar metadata is unavailable.

<a id="security-and-privacy"></a>

## Security and privacy

- No contribution reports GitHub tokens, TCP connection tokens, provider API
  keys or bearer tokens, MCP headers/URLs/commands, provider base URLs, or tool
  arguments.
- Error details expose only the exception type, not its message.
- Session summaries and login names are operational metadata; secure
  management endpoints as you would other actuator endpoints (Spring Security,
  a separate management port, or a restricted exposure list).
- System instructions are often proprietary; enable
  `copilot.endpoint.show-instructions` only on trusted management channels.

---

**Previous:** [Observability](observability.html)

**Next:** [Extension SPIs](extensions.html)
