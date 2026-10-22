---
title: Observability
---

<a id="observability"></a>

# Observability

Add `org.springframework.boot:spring-boot-starter-actuator` for conventional
Boot Micrometer setup, or provide compatible registries/handlers yourself.
No HTTP management server or network SDK health probe is added by this
integration.

| Observation or meter | Role | Integration-provided tags |
| --- | --- | --- |
| `copilot.agent.invocation` | Accepted call, including queue wait, SDK work, and cleanup. Standard observation handlers supply timers/traces. | `agent`, requested `model`, `outcome` |
| `copilot.agent.tool` | Child observation around the SDK tool handler's returned future. | `agent`, qualified `tool`, `outcome` |
| `copilot.agent.tokens` | Counters from SDK `AssistantUsageEvent` reports. Requires a meter registry. | `agent`, reported `model`, `kind` |
| `copilot.agent.rejections` | Capacity-admission rejection counter. Requires a meter registry. | `agent` |

These are Micrometer base names; exporters can transform names and add their
own tags. An omitted requested model is tagged `runtime-default`. Outcomes are
`success`, `error`, `timeout`, and `cancelled`; an operation failure takes
precedence over a caller-cancelled marker.

Token `kind` values are `input`, `output`, `cache-read`, and `cache-write`.
Only reported positive counts increment counters. Unknown counts are not
invented, negative counts are logged and ignored, and duplicate non-null SDK
event IDs are deduplicated within an invocation. These are usage counters,
not a billing ledger or context-window occupancy measurements.

Parent observations are captured at invocation admission before work moves to
a virtual thread. Tool observations explicitly retain the invocation parent,
including SDK callback execution on another thread. This does not install
automatic Reactor or Spring Security context propagation.

Prompts, replies, conversation keys, session IDs, and tool arguments are not
explicitly recorded as tags. SDK exceptions are recorded as errors and may
need application-specific redaction. Example listeners intentionally log
assistant content; that is separate from built-in telemetry.

---

**Previous:** [Reactive streaming](streaming.html)

**Next:** [Extension SPIs](extensions.html)
