---
title: Copilot Agents for Micronaut
---

<a id="copilot-micronaut"></a>

# Copilot Agents for Micronaut

Use the Copilot Java SDK in Micronaut through a managed client bean, typed agents,
and conversation-bound instances. The runtime owns the agent loop and persisted
history. Agents are registered at compile time by an annotation processor, in
keeping with Micronaut's reflection-free dependency injection, and the integration
adds configuration, lifecycle, bean tools, and optional security, management,
metrics, and Reactor streaming.

| Baseline | Version |
| --- | --- |
| Java | 25 |
| Micronaut | 5.2 (platform 5.2.3) |
| Copilot Java SDK | 1.0.24 |
| Integration | `com.microsoft:copilot-micronaut`, `0.1.0-SNAPSHOT` |

The agent API (`com.microsoft.copilot.integrations.core`: `@CopilotAgent`,
`@ConversationId`, `@UserMessage`, `CopilotAgents`, hooks, listeners, customizers,
and token resolvers) is the same one the [Spring Boot starter](../spring-boot/) and
the [Quarkus extension](../quarkus/) use, so agent interfaces move between
frameworks unchanged.

Permissions are denied unless you supply an explicit `PermissionHandler` bean or
permission policy; the integration never approves tool use implicitly. No artifacts
have been published yet.

<a id="features"></a>

## Features

- A `CopilotClient` bean for a managed local runtime, an in-process runtime, or an
  existing TCP server, with lazy (default) or eager startup and clean shutdown.
- Typed `@CopilotAgent` interfaces registered at compile time and injectable like
  any bean, returning `CompletableFuture<String>`, structured results, synchronous
  values, or `Flux<CopilotAgentEvent>`.
- `@Requires` on an agent interface makes the agent conditional, like any other bean.
- System instructions inline, from class-path resources, or from `SystemMessageConfig` beans.
- Persistent conversations with create/resume, per-conversation serialization, and
  bounded, non-blocking admission across all agents.
- Micronaut beans as tools through the SDK's `@CopilotTool` annotations.
- Declarative stdio/HTTP MCP servers and BYOK model providers per agent.
- Per-agent tool governance: allow/deny lists, declarative permission policies, and
  session hooks as beans.
- Per-user authentication through a `CopilotTokenResolver` bean or the current
  Micronaut Security `Authentication`, with `PropagatedContext` carried into tools,
  permission handlers, hooks, and listeners.
- Real cancellation: cancelling a future or `Flux`, or hitting the response
  timeout, aborts the in-flight runtime turn.
- Readiness, `/info`, a read-only management endpoint, and Micrometer metrics.
- A scripted, in-memory runtime for `@MicronautTest` and a Testcontainers `CopilotContainer`.

<a id="read-by-chapter"></a>

## Read by chapter

| Chapter | What it covers |
| --- | --- |
| [Getting started](getting-started.html) | Modules, installation, the annotation processor, runtime connection, and a first agent. |
| [Agent interfaces](agents.html) | Method contracts, routing by conversation, bound instances, conditional agents, and streaming. |
| [Beans, tools, and hooks](beans.html) | Bean references, `@CopilotTool` beans, ordering, overrides, permissions, hooks, and identity. |
| [Sessions](sessions.html) | `CopilotSessionStore` and scheduled cleanup of old agent sessions. |
| [Configuration](configuration.html) | `micronaut.copilot.*` properties, per-agent overrides, MCP servers, BYOK providers, and switches. |
| [Management and metrics](management.html) | Readiness indicator, `/info`, the `copilot` endpoint, and Micrometer meters. |
| [Testing](testing.html) | The scripted in-memory runtime and testing against a real CLI. |
| [Example application](examples.html) | A Micronaut HTTP application with conversational, bound, and streaming agents. |

<a id="build-and-preview-this-site"></a>

## Build and preview this site

The site reuses the Quarkus integration's internal Maven skin and assets so all
Java integration sites look the same. From the repository root:

```sh
java/quarkus/mvnw -f java/quarkus/pom.xml --batch-mode --no-transfer-progress -pl site-skin -am install -DskipTests
java/micronaut/mvnw -f java/micronaut/pom.xml --batch-mode --no-transfer-progress -N site
```

Open `java/micronaut/target/site/index.html`.
