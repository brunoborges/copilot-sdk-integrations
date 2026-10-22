---
title: Copilot Spring Boot
---

<a id="copilot-spring-boot"></a>

# Copilot Spring Boot

Use the Copilot Java SDK through Spring-managed clients, typed agents, and
conversation-bound instances. The runtime owns the agent loop and persisted
history; the integration supplies configuration, lifecycle, tool binding, and
optional observations and streaming.

| Baseline | Version |
| --- | --- |
| Java | 21 |
| Spring Boot | 4.1.1 |
| Copilot Java SDK | 1.0.24 |
| Integration | `io.github.brunoborges`, `0.1.0-SNAPSHOT` |

<a id="choose-your-integration"></a>

## Choose your integration

| You need | Start with |
| --- | --- |
| A first working application | [Getting started](getting-started.html) |
| Full native SDK control | [SDK client](client.html) |
| A singleton service routing calls by conversation key | [Agent interfaces](agents.html) |
| A distinct agent instance without an ID on every message | [Bound instances](instances.html) |
| Tokens and native session events | [Reactive streaming](streaming.html) |
| Property names, defaults, and validation | [Configuration reference](configuration.html) |

<a id="read-by-chapter"></a>

## Read by chapter

| Chapter | What it covers |
| --- | --- |
| [Getting started](getting-started.html) | Modules, installation, authentication prerequisites, and a minimal typed agent. |
| [SDK client](client.html) | Native access, auto-configuration, bean overrides, and client ownership. |
| [Agent interfaces](agents.html) | Annotations, method contracts, discovery, and routing. |
| [Bound instances](instances.html) | Generated identities, strict resume, handle ownership, and asynchronous cleanup. |
| [Conversations and lifecycle](conversations.html) | Create/resume decisions, serialization, errors, cancellation, and threading. |
| [System instructions](instructions.html) | Append, replace, customize, resources, sections, and transforms. |
| [Spring tool beans](tools.html) | SDK annotations, generated metadata, Spring proxies, and permissions. |
| [Events and customizers](events.html) | Session-scoped observers, native event types, and SDK configuration hooks. |
| [Configuration](configuration.html) | Every integration-owned property, precedence, and deployment examples. |
| [Reactive streaming](streaming.html) | Cold subscriptions, native events, bounded buffering, and overflow. |
| [Observability](observability.html) | Micrometer observations, token counters, privacy, and activation. |
| [Extension SPIs](extensions.html) | Custom telemetry and return-type adapters. |
| [Example application](examples.html) | Raw, routed, bound, and streaming executable modes. |
| [Security and troubleshooting](operations.html) | Authorization boundaries, failure diagnosis, and source references. |

<a id="build-and-preview-this-site"></a>

## Build and preview this site

The root project hosts the documentation for all modules. Generate it without
running child-module site goals:

```sh
./mvnw --batch-mode --no-transfer-progress -pl copilot-spring-boot-site-skin -am install
./mvnw --batch-mode --no-transfer-progress -N site
```

The first command installs the internal Maven skin and parent POM locally; it
does not build the SDK integration modules. Repeat it after changing the skin's
template. An ordinary root `install` also installs the skin.

Open `target/site/index.html`, or use Maven's local preview server:

```sh
./mvnw -N site:run
```

The preview is available at `http://localhost:8080`. It is a local documentation
server, not a Copilot runtime or the example application. Stop it with
`Ctrl+C`. Neither documentation generation nor preview makes model requests.

Chapter sources live in `src/site/markdown/`, navigation in `src/site/site.xml`,
the layout in `copilot-spring-boot-site-skin/`, and local styles/scripts in
`src/site/resources/`. Edit those sources rather
than generated files under `target/site/`. No npm/frontend build is required.

No integration artifacts have been published. Real example runs require
appropriate Copilot authentication and consume usage; documentation preview does
not require authentication.
