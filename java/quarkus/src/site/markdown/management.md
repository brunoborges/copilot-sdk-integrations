# Health, info, and endpoint

**In this chapter**

- [Activation](#activation)
- [Readiness check](#readiness-check)
- [Copilot endpoint](#copilot-endpoint)
- [Info contribution](#info-contribution)
- [Security and privacy](#security-and-privacy)

<a id="activation"></a>

## Activation

Quarkus has no Actuator; each contribution plugs into the matching Quarkus
extension and is decided at build time. A contribution is registered only when
`quarkus.copilot.enabled` is not `false`, its own build-time switch is on, and
(except for the endpoint) the matching extension is present. All of them use the
`CopilotClient` bean, whether extension-produced or application-provided.

| Contribution | Type | Requires | Enabled by default | Build-time switch |
| --- | --- | --- | --- | --- |
| `copilot` readiness check | `com.microsoft.copilot.integrations.quarkus.runtime.health.CopilotHealthCheck` | `quarkus-smallrye-health` | Yes | `quarkus.copilot.health.enabled` |
| `/q/copilot` endpoint | `com.microsoft.copilot.integrations.quarkus.runtime.endpoint.CopilotEndpoint` | `quarkus-vertx-http` (build fails without it) | No | `quarkus.copilot.endpoint.enabled` |
| `copilot` info section | `com.microsoft.copilot.integrations.quarkus.runtime.info.CopilotInfoContributor` | `quarkus-info` | Yes | `quarkus.copilot.info.enabled` |

Runtime tuning lives under `quarkus.copilot.health.*` and
`quarkus.copilot.endpoint.*`; see the [configuration reference](configuration.html).

**Lazy clients are never started by diagnostics.** The SDK starts a
disconnected client on the first runtime call, so the readiness check and
endpoint operations inspect `CopilotClient.getState()` first and only call the
runtime when the client is already `CONNECTED`. Set
`quarkus.copilot.health.start-client=true` or
`quarkus.copilot.endpoint.start-client=true` to let them connect a lazy client,
or use `quarkus.copilot.eager-start=true` to connect during startup.

<a id="readiness-check"></a>

## Readiness check

The check is a MicroProfile `@Readiness` check named `copilot`, so it appears in
`/q/health` and `/q/health/ready`. MicroProfile Health has only `UP` and `DOWN`;
a client that simply has not connected yet is not a readiness failure.

| Client state | Status | Runtime calls |
| --- | --- | --- |
| `DISCONNECTED` (lazy, not started yet) | `UP` with a `reason` | None, unless `quarkus.copilot.health.start-client=true` |
| `CONNECTING` | `UP` with a `reason` | None, unless `quarkus.copilot.health.start-client=true` |
| `ERROR` | `DOWN` with a `reason` | None |
| `CONNECTED`, authenticated | `UP` | `getStatus()` and `getAuthStatus()` |
| `CONNECTED`, not authenticated | `DOWN` (or `UP` when `quarkus.copilot.health.require-authentication=false`) | `getStatus()` and `getAuthStatus()` |
| Runtime error or no response within `quarkus.copilot.health.timeout` | `DOWN` with `reason` and `error` type | — |

The `quarkus.copilot.health.timeout` budget (default `5s`) covers starting the
client (when enabled) and both status calls. Set `require-authentication=false`
for applications that use only [BYOK providers](byok.html), which do not
require a GitHub login.

```ini
quarkus.copilot.health.timeout=10s
quarkus.copilot.health.require-authentication=false
```

Example response from `/q/health/ready`:

```json
{
  "status": "UP",
  "checks": [
    {
      "name": "copilot",
      "status": "UP",
      "data": {
        "state": "CONNECTED",
        "version": "1.2.3",
        "protocolVersion": 3,
        "authenticated": true,
        "authType": "user",
        "host": "https://github.com",
        "login": "octocat"
      }
    }
  ]
}
```

Under the scripted test runtime the client reports `CONNECTED` but does not
answer status queries, so the check reports `DOWN` there; see
[Testing](testing.html#supported-operations-and-limits).

<a id="copilot-endpoint"></a>

## Copilot endpoint

The endpoint is off by default. Enable it at build time:

```ini
quarkus.copilot.endpoint.enabled=true
```

It is served as a blocking framework route under the non-application root path
(`/q` unless `quarkus.http.non-application-root-path` changes it). All
operations are read-only `GET`s returning JSON; an unknown section returns `404`.

| Operation | Path | Contacts the runtime |
| --- | --- | --- |
| Overview: client state, agents, and execution capacity | `GET /q/copilot` | No |
| Registered agents | `GET /q/copilot/agents` | No |
| In-flight/queued invocations and limits | `GET /q/copilot/execution` | No |
| Available models | `GET /q/copilot/models` | Only when connected (or `quarkus.copilot.endpoint.start-client=true`) |
| Runtime sessions | `GET /q/copilot/sessions` | Only when connected (or `quarkus.copilot.endpoint.start-client=true`) |

Each agent entry contains its logical `name`, interface `type`, whether it is a
`bound` (ID-less) definition, effective `model` and `reasoningEffort`,
`responseTimeout`, provider `name` and `type`, in-process `tools` names, selected
`mcpServers` names, and the `instructionsMode`. Instruction content
(`instructions`) is `null` unless `quarkus.copilot.endpoint.show-instructions=true`.

`execution` reports `running` (holding one of
`quarkus.copilot.execution.max-concurrent-invocations` permits), `queued`
(accepted but waiting for a permit or for an earlier call to the same
conversation), and both configured limits as `maxConcurrentInvocations` and
`maxOutstandingInvocations`.

`models` and `sessions` return `{"state", "reason", ...}`. When the client is not
connected, or the runtime fails or exceeds `quarkus.copilot.endpoint.timeout`
(default `5s`), the list is `null` and `reason` explains why; the request itself
does not fail. Models list `id`, `name`, `policyState`,
`supportedReasoningEfforts`, and `defaultReasoningEffort`. Sessions list
`sessionId`, `summary`, `startTime`, `modifiedTime`, `remote`, and `managed`.
By default only sessions created by this extension (`agent-` prefix) are listed;
set `quarkus.copilot.endpoint.include-all-sessions=true` to include all runtime
sessions. `summary` is `null` unless
`quarkus.copilot.endpoint.show-session-summaries=true`, because runtime-generated
summaries can contain prompt or business content.

The JSON is written with the application's `ObjectMapper` bean when one is
available.

<a id="info-contribution"></a>

## Info contribution

With `quarkus-info`, `/q/info` gains a `copilot` section that never contacts the
runtime:

```json
{
  "copilot": {
    "extension": { "version": "0.1.0-SNAPSHOT" },
    "sdk": { "version": "1.0.24" },
    "connection": { "mode": "stdio" }
  }
}
```

`connection.mode` is derived from the `CopilotClientOptions` bean: `stdio`,
`tcp`, `external-server` (`quarkus.copilot.cli-url`), `in-process`, `custom`, or
`unknown` when an application supplies its own client without an options bean.
Addresses and tokens are not reported. Versions fall back to `unknown` when the
jar metadata is unavailable.

<a id="security-and-privacy"></a>

## Security and privacy

- No contribution reports GitHub tokens, TCP connection tokens, provider API
  keys or bearer tokens, MCP headers/URLs/commands, provider base URLs, or tool
  arguments.
- Error details expose only the exception type, not its message.
- The extension applies no authorization to `/q/copilot`. Session summaries and
  login names are operational metadata; protect the path as you would other
  `/q` endpoints, for example with an HTTP permission:

  ```ini
  quarkus.http.auth.permission.copilot.paths=/q/copilot,/q/copilot/*
  quarkus.http.auth.permission.copilot.policy=authenticated
  ```

- System instructions are often proprietary; enable
  `quarkus.copilot.endpoint.show-instructions` only on trusted management channels.

---

**Previous:** [Observability](observability.html)

**Next:** [Extension SPIs](extensions.html)
