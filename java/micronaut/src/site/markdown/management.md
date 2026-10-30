---
title: Management and metrics
---

<a id="management"></a>

# Management and metrics

Optional integrations activate when the matching module is on the class path.

<a id="health-info-endpoint"></a>

## Health, info, and endpoint (`micronaut-management`)

- A `copilot` readiness indicator that never force-starts a lazy client
  (`micronaut.copilot.health.enabled`, tuned with
  `micronaut.copilot.health.{timeout,start-client,require-authentication}`).
- A `copilot` section in `/info` (`micronaut.copilot.info.enabled`).
- A read-only `copilot` management endpoint at `/copilot` and
  `/copilot/{agents,execution,models,sessions}` that reports agents, execution
  capacity, models, and sessions. It is disabled by default; enable it with
  `endpoints.copilot.enabled=true`. It is sensitive by default, so secure it or set
  `endpoints.copilot.sensitive=false`. Tune it with
  `micronaut.copilot.endpoint.{timeout,start-client,show-instructions,include-all-sessions,show-session-summaries}`.

<a id="metrics"></a>

## Metrics (`micronaut-micrometer-core`)

When a Micrometer `MeterRegistry` is present and `micronaut.copilot.metrics.enabled`
is `true`, the integration records `copilot.agent.invocation` and
`copilot.agent.tool` timers, plus token and rejection counters.
