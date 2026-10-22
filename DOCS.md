# Documentation

The API and configuration reference is organized into focused Maven Site
chapters. The chapter sources below are canonical; this file is the
repository-friendly entry point.

Website: [Copilot Spring Boot documentation](https://brunoborges.github.io/copilot-spring-boot/).

## Table of contents

| Chapter | Coverage |
| --- | --- |
| [Documentation home](src/site/markdown/index.md) | Integration paths, versions, and site build/preview commands. |
| [Getting started](src/site/markdown/getting-started.md) | Modules, installation, authentication prerequisites, and a minimal agent. |
| [SDK client](src/site/markdown/client.md) | Native SDK access, auto-configuration, bean overrides, and ownership. |
| [Agent interfaces](src/site/markdown/agents.md) | Annotations, routing, supported signatures, and discovery. |
| [Bound instances](src/site/markdown/instances.md) | Generated identities, strict resume, parameterized Spring lookup, and lifecycle. |
| [Conversations and lifecycle](src/site/markdown/conversations.md) | Persistence, serialization, threading, errors, and cancellation. |
| [System instructions](src/site/markdown/instructions.md) | Append, replace, customize, resources, sections, and transforms. |
| [Spring tool beans](src/site/markdown/tools.md) | SDK annotations, generated metadata, proxies, and permissions. |
| [MCP servers](src/site/markdown/mcp.md) | Local/remote definitions, per-agent bindings, native SDK beans, and security. |
| [BYOK providers](src/site/markdown/byok.md) | Bring-your-own-key model providers per agent, native SDK beans, and security. |
| [Events and customizers](src/site/markdown/events.md) | Session-scoped listeners, native events, and SDK configuration hooks. |
| [Configuration reference](src/site/markdown/configuration.md) | All supported keys, defaults, validation, precedence, and examples. |
| [Reactive streaming](src/site/markdown/streaming.md) | Cold subscriptions, native events, buffering, overflow, and cancellation. |
| [Observability](src/site/markdown/observability.md) | Micrometer observations, token counters, tags, activation, and privacy. |
| [Actuator](src/site/markdown/actuator.md) | Health indicator, info contribution, read-only `copilot` endpoint, and privacy. |
| [Extension SPIs](src/site/markdown/extensions.md) | Telemetry replacement and custom return-type adapters. |
| [Example application](src/site/markdown/examples.md) | Raw SDK, routed, bound-instance, and streaming modes. |
| [Security and troubleshooting](src/site/markdown/operations.md) | Authorization boundaries, failure diagnosis, limitations, and source links. |

## Build and preview

The site uses Maven Site Plugin 3.22.0, Doxia Markdown, and a small project-owned
Maven skin. No npm or frontend build is required.

Install the skin and parent POM, then render the root documentation:

```sh
./mvnw --batch-mode --no-transfer-progress -pl copilot-spring-boot-site-skin -am install
./mvnw --batch-mode --no-transfer-progress -N site
```

Open `target/site/index.html`, or start a local preview:

```sh
./mvnw -N site:run
```

Browse `http://localhost:8080` and stop the server with `Ctrl+C`.
Documentation generation and preview do not make model requests or require
Copilot authentication. The `-N` flag intentionally limits site generation to
the root project rather than child-module reports.

## Bound agent instances

The [bound instance chapter](src/site/markdown/instances.md) documents
`CopilotAgents.create/resume`, `CopilotAgentInstance<T>`, optional generated
conversation keys, strict lazy history checks, close barriers, and reactive
cleanup. It also shows an application prototype-bean adapter for
`ObjectProvider.getObject(conversationId)`.

This heading preserves the existing `DOCS.md#bound-agent-instances` entry point.

## Configuration reference

The [configuration chapter](src/site/markdown/configuration.md) is the complete
reference for `copilot.*`, `copilot.agents.<name>.*`, `copilot.mcp.servers.*`,
`copilot.providers.*`, `copilot.execution.*`,
`copilot.observations.enabled`, and `copilot.reactor.buffer-capacity`.
Settings not listed there are not automatically forwarded to SDK configuration.

## Editing documentation

| Location | Purpose |
| --- | --- |
| `src/site/markdown/` | Canonical chapter content. |
| `src/site/site.xml` | Chapter navigation, external links, and theme bootstrap. |
| `src/site/resources/` | Local CSS and small accessibility/navigation enhancements. |
| `copilot-spring-boot-site-skin/` | Maven skin template and metadata. |
| `target/site/` | Generated output; do not edit or commit it. |

Reinstall the skin after template changes. Re-run the root `site` goal after
chapter, navigation, or resource changes. Chapter-to-chapter HTML links are
intended for the rendered site; the table above provides direct Markdown source
links for repository browsing.

After changing the shared layout or site descriptor, use `./mvnw -N clean site`
to regenerate otherwise unchanged chapters as well.
