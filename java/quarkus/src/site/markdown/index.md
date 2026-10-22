---
title: Copilot Quarkus
---

<a id="copilot-quarkus"></a>

# Copilot Quarkus

Use the Copilot Java SDK in Quarkus through a CDI-managed client, typed agents,
and conversation-bound instances. The runtime owns the agent loop and persisted
history. The extension discovers agents at build time and provides configuration,
lifecycle, tool binding, and optional health, metrics, and streaming.

| Baseline | Version |
| --- | --- |
| Java | 21 |
| Quarkus | 3.40.1 |
| Copilot Java SDK | 1.0.24 |
| Extension | `io.github.brunoborges:copilot-quarkus`, `0.1.0-SNAPSHOT` |

The agent API (`io.github.brunoborges.copilot.agent`) is the same one the
[Spring Boot starter](../spring-boot/) uses, so agent interfaces, hooks, listeners,
and customizers move between the two frameworks unchanged. See
[Coming from Spring Boot](spring-boot.html) for the differences in wiring.

<a id="choose-your-integration"></a>

## Choose your integration

| You need | Start with |
| --- | --- |
| A first working application | [Getting started](getting-started.html) |
| Full native SDK control | [SDK client](client.html) |
| A singleton bean routing calls by conversation key | [Agent interfaces](agents.html) |
| A distinct agent instance without an ID on every message | [Bound instances](instances.html) |
| Tokens and native session events as a `Multi` | [Mutiny streaming](streaming.html) |
| Tools from local or remote MCP servers | [MCP servers](mcp.html) |
| Property names, defaults, and validation | [Configuration reference](configuration.html) |
| To port an existing Spring Boot application | [Coming from Spring Boot](spring-boot.html) |

<a id="read-by-chapter"></a>

## Read by chapter

| Chapter | What it covers |
| --- | --- |
| [Getting started](getting-started.html) | Modules, installation, authentication prerequisites, and a minimal typed agent. |
| [SDK client](client.html) | Native access, the CDI producer, bean overrides, and client ownership. |
| [Coming from Spring Boot](spring-boot.html) | Mapping of starter concepts, properties, and modules to the extension. |
| [Agent interfaces](agents.html) | Annotations, method contracts, build-time discovery, and routing. |
| [Bound instances](instances.html) | Generated identities, strict resume, handle ownership, and asynchronous cleanup. |
| [Conversations and lifecycle](conversations.html) | Create/resume decisions, serialization, errors, cancellation, and threading. |
| [System instructions](instructions.html) | Append, replace, customize, resources, sections, and transforms. |
| [CDI tool beans](tools.html) | SDK annotations, generated metadata, client proxies, and permissions. |
| [MCP servers](mcp.html) | Stdio/HTTP definitions, per-agent selection, SDK beans, tool allowlists, and lifecycle. |
| [BYOK providers](byok.html) | Your own OpenAI, Azure, Anthropic, or local model provider per agent. |
| [Per-user authentication](authentication.html) | Per-user GitHub tokens, the `SecurityIdentity` resolver, and caller context in tools. |
| [Events and customizers](events.html) | Session-scoped observers, native event types, and SDK configuration hooks. |
| [Tool governance, permissions, and hooks](governance.html) | Tool allow/deny lists, built-in tool control, permission policies, and hook beans. |
| [Configuration](configuration.html) | Every runtime and build-time property, precedence, and examples. |
| [Mutiny streaming](streaming.html) | Cold subscriptions, native events, bounded buffering, and overflow. |
| [Observability](observability.html) | Micrometer timers, token counters, privacy, and activation. |
| [Health, info, and endpoint](management.html) | SmallRye Health check, `/q/info` section, and the read-only `/q/copilot` endpoint. |
| [Extension SPIs](extensions.html) | Custom telemetry and return-type adapters. |
| [Testing](testing.html) | Scripted in-memory runtime in `@QuarkusTest`, recorded interactions, and Testcontainers. |
| [Example application](examples.html) | A Quarkus REST application with routed, bound, and streaming agents. |
| [Security and troubleshooting](operations.html) | Authorization boundaries, failure diagnosis, limitations, and source references. |

<a id="build-and-preview-this-site"></a>

## Build and preview this site

From the `quarkus/` directory, install the internal skin and parent POM, then
render the root documentation without running child-module site goals:

```sh
./mvnw --batch-mode --no-transfer-progress -pl site-skin -am install
./mvnw --batch-mode --no-transfer-progress -N site
```

Open `target/site/index.html`, or start Maven's local preview server:

```sh
./mvnw -N site:run
```

The preview is available at `http://localhost:8080`; stop it with `Ctrl+C`.
Neither documentation generation nor preview makes model requests or requires
Copilot authentication.

Chapter sources live in `src/site/markdown/`, navigation in `src/site/site.xml`,
the layout in `site-skin/`, and local styles and scripts in `src/site/resources/`.
Edit those sources rather than generated files under `target/site/`. Code blocks
are highlighted with a locally bundled Highlight.js; use language labels such as
`java`, `xml`, `ini` (for `application.properties`), `json`, or `sh`.

No extension artifacts have been published. Real example runs require
appropriate Copilot authentication and consume usage.
