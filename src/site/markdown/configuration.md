---
title: Configuration reference
---

<a id="configuration-reference"></a>

# Configuration reference

**In this chapter**

- [Client properties](#client-properties)
- [Per-agent properties](#per-agent-properties)
- [Execution properties](#execution-properties)
- [Observation properties](#observation-properties)
- [Reactor properties](#reactor-properties)
- [Configuration examples and precedence](#configuration-examples-and-precedence)

Configuration uses ordinary Spring Boot property sources: application files,
profiles, environment values, and command-line arguments. Use explicit duration
units such as `30s` or `250ms`.

Only the integration-owned keys below are mapped automatically. Arbitrary
`SessionConfig` fields are not forwarded from similarly named properties.
Use SDK beans/customizers for settings not listed here.

<a id="client-properties"></a>

## Client properties

These configure the starter-owned client, not individual conversations.
The binding type is `io.github.brunoborges.copilot.autoconfigure.CopilotProperties`.

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `copilot.enabled` | Boolean | `true` | Enable client and agent integration, including optional adapters/instrumentation. |
| `copilot.eager-start` | Boolean | `false` | Connect during managed client creation instead of first use. |
| `copilot.startup-timeout` | Duration | `30s` | Positive eager connection wait; must fit signed 64-bit nanoseconds. Does not bound subsequent cleanup. |
| `copilot.cli-path` | String | Unset | Explicit local executable; non-blank if supplied. SDK resolves the runtime when omitted. |
| `copilot.cli-args` | List of strings | Empty | Additional local runtime arguments; blank elements are rejected. |
| `copilot.working-directory` | String | Unset | Local process working directory; omitted values retain SDK defaults. Not a per-session working directory. |
| `copilot.log-level` | String | SDK default, `info` | Non-blank, forwarded to SDK/CLI without an integration-specific level enum. |
| `copilot.cli-url` | String | Unset | Existing raw-TCP server address; switches the default options away from stdio. |
| `copilot.tcp-connection-token` | String | Unset | Non-blank external-server token; requires `cli-url`. |

`cli-url` cannot be combined with `cli-path`, non-empty `cli-args`, or
`working-directory`. Supported address forms include `4321`, `localhost:4321`,
`http://localhost:4321`, and `[::1]:4321`. Ports must be 1-65535. URI credentials,
paths (including a trailing `/`), queries, and fragments are rejected.

**SDK 1.0.24 uses raw TCP for external-server connections. Neither `http://`
nor `https://` enables HTTP or TLS.** Use a trusted local connection or a
separately secured tunnel, especially when sending a connection token. The
external server owns its GitHub authentication and process lifecycle.

There is no integration property for a GitHub credential. Local authentication
follows SDK/CLI rules; external secrets should use supported environment or
application secret mechanisms, never committed literals.

<a id="per-agent-properties"></a>

## Per-agent properties

Replace `<name>` with the logical `@CopilotAgent` name:

The binding type is `CopilotAgentsProperties`, whose `agents()` map contains
`CopilotAgentsProperties.Agent` records, in the auto-configuration package.

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `copilot.agents.<name>.model` | String | Annotation value, then runtime default | Non-blank override of `model`. |
| `copilot.agents.<name>.reasoning-effort` | String | Annotation value, then runtime default | Non-blank override of `reasoningEffort`; support depends on the selected model. |
| `copilot.agents.<name>.response-timeout` | Duration | `60s` | At least `1ms`, representable in signed 64-bit nanoseconds. Bounds SDK response waiting only. |

Response waiting excludes queueing, connection/session metadata lookup,
creation/resumption, and cleanup. It is not a total request deadline.
Instructions, tools, listeners, and bean references are annotation/bean
configuration, not additional properties under this map.

Explicit lower-case, kebab-case names make property configuration easiest.
For keys containing punctuation that Spring Boot map binding would otherwise
interpret or normalize, use quoted bracket notation:

```yaml
copilot:
  agents:
    "[customer.agent]":
      response-timeout: 30s
```

<a id="execution-properties"></a>

## Execution properties

These limits are shared by all typed agents in one application context.
The binding type is `CopilotAgentExecutionProperties` in the auto-configuration
package.

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `copilot.execution.max-concurrent-invocations` | Integer | `8` | Positive maximum of invocations performing SDK work, including response waiting and cleanup. |
| `copilot.execution.max-outstanding-invocations` | Integer | `64` | Running plus queued/serialized accepted calls; must be at least the concurrency limit. |

Admission does not block the caller. A full envelope returns a
`RejectedExecutionException` without contacting the SDK. Outstanding counts
include calls serialized behind another call to the same conversation, not
just tasks waiting for a global slot. These are client invocation bounds, not
a guarantee about detached work continuing in the runtime.

<a id="observation-properties"></a>

## Observation properties

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `copilot.observations.enabled` | Boolean | `true` | Enable built-in Micrometer integration when its classpath/registry conditions are met. Does not disable an application-provided telemetry bean. |

The Micrometer observation and core libraries and an `ObservationRegistry` bean
are required for built-in instrumentation. A `MeterRegistry` bean is optional.
Adding Spring Boot Actuator is the usual way to supply the registries/handlers.
Without activation, ordinary agent use works with no-op telemetry.

<a id="reactor-properties"></a>

## Reactor properties

These apply only when the optional Reactor module is present.
The binding type is `io.github.brunoborges.copilot.reactor.CopilotReactorProperties`.

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `copilot.reactor.buffer-capacity` | Integer | `256` | Positive maximum events buffered in the adapter queue per subscription. |

This is not a bound on buffers introduced by downstream Reactor operators.
There is no separate `copilot.reactor.enabled` property; `copilot.enabled`
controls integration activation.

<a id="configuration-examples-and-precedence"></a>

## Configuration examples and precedence

Example local configuration, including optional groups when those modules are
installed:

```yaml
copilot:
  enabled: true
  cli-path: /absolute/path/to/copilot
  eager-start: false
  startup-timeout: 30s
  agents:
    spring-advisor:
      response-timeout: 60s
      # model: an-available-model-id
      # reasoning-effort: high
  execution:
    max-concurrent-invocations: 8
    max-outstanding-invocations: 64
  observations:
    enabled: true
  reactor:
    buffer-capacity: 256
```

Alternative external-server configuration; do not add the local-only settings:

```yaml
copilot:
  cli-url: localhost:4321
  tcp-connection-token: ${COPILOT_SERVER_TOKEN}
```

Omit the token property if the server does not require one. The placeholder is
an application/environment secret, not a new SDK GitHub authentication property.

Precedence is:

1. Spring Boot resolves property sources using its standard rules.
2. Per-agent `model`/`reasoning-effort` properties override annotation values.
3. Omitted model/reasoning settings leave those choices to SDK/runtime defaults.
4. Customizers run after declarative session settings, subject to the managed
   ID/permission/streaming invariants described above.

For example, `--copilot.agents.spring-advisor.response-timeout=30s` overrides
the value supplied through an application file. Supplying a native client or
options bean changes ownership/mapping as described below.

---

**Previous:** [Events and customizers](events.html)

**Next:** [Reactive streaming](streaming.html)
