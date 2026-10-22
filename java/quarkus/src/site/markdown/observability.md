# Observability

Add `io.quarkus:quarkus-micrometer` together with a registry extension such as
`quarkus-micrometer-registry-prometheus`. When the Micrometer capability is
present and the build-time switch `quarkus.copilot.metrics.enabled` is not
`false`, the extension registers `MicrometerCopilotAgentTelemetry` and records
the meters below. The integration adds no HTTP server of its own; meters are
exported through the registry you choose (for example `/q/metrics`). With
SmallRye Health, `quarkus-info`, or the opt-in endpoint it also contributes a
readiness check, an info section, and diagnostics; see
[Health, info, and endpoint](management.html).

| Meter | Role | Integration-provided tags |
| --- | --- | --- |
| `copilot.agent.invocation` | Timer for an accepted call, including queue wait, SDK work, and cleanup. | `agent`, requested `model`, `outcome` |
| `copilot.agent.tool` | Timer around the SDK tool handler's returned future. | `agent`, qualified `tool`, `outcome` |
| `copilot.agent.tokens` | Counters from SDK `AssistantUsageEvent` reports. | `agent`, reported `model`, `kind` |
| `copilot.agent.rejections` | Capacity-admission rejection counter. | `agent` |

These are Micrometer base names; registries transform them (Prometheus, for
example, exports `copilot_agent_invocation_seconds`) and can add their own tags
through Quarkus Micrometer configuration. An omitted requested model is tagged
`runtime-default`. Outcomes are `success`, `error`, `timeout`, and `cancelled`;
an operation failure takes precedence over a caller-cancelled marker. Tool names
are qualified as `namespace.name` when the SDK tool has a namespace.

Token `kind` values are `input`, `output`, `cache-read`, and `cache-write`.
Only reported positive counts increment counters. Unknown counts are not
invented, negative counts are logged and ignored, and duplicate non-null SDK
event IDs are deduplicated within an invocation. A usage event without a model
is attributed to the requested model tag. These are usage counters, not a
billing ledger or context-window occupancy measurements.

The extension records timers and counters only: it does not create
OpenTelemetry spans or Micrometer observations, so there is no trace parent to
propagate. What it does propagate is the caller's CDI request context, captured
at invocation admission on the calling thread and re-activated on the virtual
and SDK threads that run tools, permission handlers, hooks, and listeners (see
[Security and troubleshooting](operations.html#security-and-privacy)).

<a id="custom-telemetry"></a>

## Custom telemetry

All instrumentation goes through the `CopilotAgentTelemetry` SPI. The default
bean is the Micrometer implementation when available, otherwise a no-op.

- Declare your own `CopilotAgentTelemetry` bean to replace it. The default is a
  `@DefaultBean`, so yours wins; inject
  `io.github.brunoborges.copilot.quarkus.runtime.CopilotMetricsTelemetry` if you
  want to delegate to the Micrometer implementation.
- Declare `io.github.brunoborges.copilot.quarkus.runtime.CopilotAgentTelemetryDecorator`
  beans (a `UnaryOperator<CopilotAgentTelemetry>`) to wrap the effective
  telemetry instead of replacing it. The scripted test runtime uses this hook to
  attribute requests to agents.
- Set `quarkus.copilot.metrics.enabled=false` (build time) to keep Micrometer on
  the class path without Copilot meters.

Prompts, replies, conversation keys, session IDs, and tool arguments are not
recorded as tags. Error outcomes record only a category; exception details are
not attached to meters. Example listeners intentionally log assistant content;
that is separate from built-in telemetry.

---

**Previous:** [Mutiny streaming](streaming.html)

**Next:** [Health, info, and endpoint](management.html)
