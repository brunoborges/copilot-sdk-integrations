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
| Tools from local or remote MCP servers | [MCP servers](mcp.html) |
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
| [MCP servers](mcp.html) | Stdio/HTTP definitions, per-agent selection, SDK beans, tool allowlists, and lifecycle. |
| [BYOK providers](byok.html) | Your own OpenAI, Azure, Anthropic, or local model provider per agent, without a Copilot subscription. |
| [Events and customizers](events.html) | Session-scoped observers, native event types, and SDK configuration hooks. |
| [Tool governance, permissions, and hooks](governance.html) | Tool allow/deny lists, built-in tool control, permission policies, and hook beans. |
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

Fenced code blocks use a locally bundled Highlight.js 11.12.0 browser build.
Use language labels such as `java`, `xml`, `yaml`, `json`, or `sh`; unlabelled
or explicitly plain-text blocks remain plain. Syntax colors follow the same
light/dark theme as the documentation. The header's **Theme** selector offers
**System**, **Light**, and **Dark**. Your choice is remembered across visits;
System follows changes to your device preference. A valid `?theme=light`
or `?theme=dark` link overrides the saved choice until you change it.
The version, checksum, source URL, and
BSD license are recorded under `src/site/resources/js/vendor/`. Keep the
license and notice when updating the bundle; no runtime CDN request is made.

No integration artifacts have been published. Real example runs require
appropriate Copilot authentication and consume usage; documentation preview does
not require authentication.
