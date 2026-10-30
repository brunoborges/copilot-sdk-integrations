---
title: Configuration
---

<a id="configuration"></a>

# Configuration

All settings live under `micronaut.copilot`. The keys and defaults match the
Spring Boot starter's `copilot.*` properties.

```properties
micronaut.copilot.eager-start=false
micronaut.copilot.startup-timeout=30s
micronaut.copilot.execution.max-concurrent-invocations=8
micronaut.copilot.execution.max-outstanding-invocations=64

# Per-agent overrides of @CopilotAgent attributes
micronaut.copilot.agents.micronaut-advisor.model=gpt-5.2-codex
micronaut.copilot.agents.micronaut-advisor.response-timeout=2m
micronaut.copilot.agents.micronaut-advisor.excluded-tools=shell
micronaut.copilot.agents.micronaut-advisor.permissions.allow=read
micronaut.copilot.agents.micronaut-advisor.mcp-servers=github
# Defer MCP tool definitions behind tool search once the agent has 20+ tools
micronaut.copilot.agents.micronaut-advisor.tool-search.defer-threshold=20
micronaut.copilot.agents.micronaut-advisor.provider=foundry
# Prompt provenance: user, system, or agent-<id> (none clears @CopilotAgent(messageSource)).
# Caller-supplied, not an authenticated identity; never authorize from it.
micronaut.copilot.agents.micronaut-advisor.message-source=system

# MCP servers
micronaut.copilot.mcp.servers.github.type=http
micronaut.copilot.mcp.servers.github.url=https://api.githubcopilot.com/mcp/

# BYOK providers
micronaut.copilot.providers.foundry.base-url=https://my-resource.openai.azure.com/openai/v1/
micronaut.copilot.providers.foundry.api-key=${FOUNDRY_API_KEY}
micronaut.copilot.providers.foundry.wire-api=responses
```

<a id="connection"></a>

## Runtime connection

| Property | Effect |
| --- | --- |
| `micronaut.copilot.cli-path` | Path to an installed Copilot CLI to start as a child process. |
| `micronaut.copilot.cli-url` | Connect to an existing TCP server instead of starting one. |
| `micronaut.copilot.connection` | `stdio` or the experimental `inprocess`. See [Getting started](getting-started.html#connect-to-the-runtime). |

<a id="switches"></a>

## Switches

| Property | Default | Effect |
| --- | --- | --- |
| `micronaut.copilot.enabled` | `true` | Registers the client, agents, and integrations. |
| `micronaut.copilot.health.enabled` | `true` | Adds the `copilot` readiness indicator when `micronaut-management` is present. |
| `micronaut.copilot.metrics.enabled` | `true` | Times agents and tools when a Micrometer `MeterRegistry` is present. |
| `micronaut.copilot.info.enabled` | `true` | Adds a `copilot` section to `/info` when `micronaut-management` is present. |
| `endpoints.copilot.enabled` | `false` | Serves `/copilot` and `/copilot/{agents,execution,models,sessions}`. The endpoint is sensitive by default; set `endpoints.copilot.sensitive=false` or secure it. |
| `micronaut.copilot.security.identity-token-resolver.enabled` | `false` | Authenticates agents as the current Micronaut Security user, using the token in its `micronaut.copilot.security.identity.token-attribute` attribute (default `accessToken`). |
| `micronaut.copilot.test-runtime.enabled` | `true` | Lets `copilot-micronaut-test` replace the client in tests. |

The readiness indicator and endpoint are tuned with
`micronaut.copilot.health.{timeout,start-client,require-authentication}` and
`micronaut.copilot.endpoint.{timeout,start-client,show-instructions,include-all-sessions,show-session-summaries}`.
Reactor streams buffer up to `micronaut.copilot.streaming.buffer-capacity`
(default `256`) events. Session store and cleanup properties are described in
[Sessions](sessions.html).

Agents and their bean references are validated at startup, so a missing tool bean
or an unknown MCP server fails the application instead of the first request.
