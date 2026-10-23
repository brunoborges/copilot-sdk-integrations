# Copilot SDK Agentic Integrations

[![License: MIT](https://img.shields.io/github/license/brunoborges/copilot-sdk-integrations)](LICENSE)
[![Docs](https://github.com/brunoborges/copilot-sdk-integrations/actions/workflows/pages.yml/badge.svg)](https://brunoborges.github.io/copilot-sdk-integrations/)
[![GitHub stars](https://img.shields.io/github/stars/brunoborges/copilot-sdk-integrations?style=flat)](https://github.com/brunoborges/copilot-sdk-integrations/stargazers)
[![Last commit](https://img.shields.io/github/last-commit/brunoborges/copilot-sdk-integrations)](https://github.com/brunoborges/copilot-sdk-integrations/commits/main)

[![Java](https://img.shields.io/badge/Java-21%2B-ED8B00?logo=openjdk&logoColor=white)](java/)
[![.NET](https://img.shields.io/badge/.NET-512BD4?logo=dotnet&logoColor=white)](dotnet/)
[![Python](https://img.shields.io/badge/Python-3.11%2B-3776AB?logo=python&logoColor=white)](python/)
[![Node.js](https://img.shields.io/badge/Node.js-20%2B-339933?logo=nodedotjs&logoColor=white)](nodejs/)

[![Spring Boot](https://github.com/brunoborges/copilot-sdk-integrations/actions/workflows/build.yml/badge.svg)](https://github.com/brunoborges/copilot-sdk-integrations/actions/workflows/build.yml)
[![Quarkus](https://github.com/brunoborges/copilot-sdk-integrations/actions/workflows/quarkus.yml/badge.svg)](https://github.com/brunoborges/copilot-sdk-integrations/actions/workflows/quarkus.yml)
[![Micronaut](https://github.com/brunoborges/copilot-sdk-integrations/actions/workflows/micronaut.yml/badge.svg)](https://github.com/brunoborges/copilot-sdk-integrations/actions/workflows/micronaut.yml)
[![Camel](https://github.com/brunoborges/copilot-sdk-integrations/actions/workflows/camel.yml/badge.svg)](https://github.com/brunoborges/copilot-sdk-integrations/actions/workflows/camel.yml)
[![.NET CI](https://github.com/brunoborges/copilot-sdk-integrations/actions/workflows/dotnet.yml/badge.svg)](https://github.com/brunoborges/copilot-sdk-integrations/actions/workflows/dotnet.yml)
[![Python CI](https://github.com/brunoborges/copilot-sdk-integrations/actions/workflows/python.yml/badge.svg)](https://github.com/brunoborges/copilot-sdk-integrations/actions/workflows/python.yml)
[![Node.js CI](https://github.com/brunoborges/copilot-sdk-integrations/actions/workflows/nodejs.yml/badge.svg)](https://github.com/brunoborges/copilot-sdk-integrations/actions/workflows/nodejs.yml)

Framework integrations for the [GitHub Copilot SDKs](https://github.com/github/copilot-sdk).

The Copilot SDKs give you a low-level client for the Copilot runtime. This repository
adds idiomatic integration layers for popular application frameworks, so the client,
agents, tools, and sessions feel native to each framework: configuration, dependency
injection, lifecycle management, security context, observability, and testing.

📖 **[Documentation](https://brunoborges.github.io/copilot-sdk-integrations/)**

## Integrations

| Language | Framework / library | Directory | Status |
| --- | --- | --- | --- |
| Java | [Spring Boot](https://spring.io/projects/spring-boot) | [`java/spring-boot/`](java/spring-boot/) | Available (unreleased) |
| Java | [Quarkus](https://quarkus.io/) | [`java/quarkus/`](java/quarkus/) | Available (unreleased) |
| Java | [Micronaut](https://micronaut.io/) | [`java/micronaut/`](java/micronaut/) | Available (unreleased) |
| Java | [Apache Camel](https://camel.apache.org/) | [`java/camel/`](java/camel/) | Available (unreleased) |
| .NET | Generic Host / ASP.NET Core ([Microsoft.Extensions.AI](https://learn.microsoft.com/dotnet/ai/microsoft-extensions-ai) `IChatClient`) | [`dotnet/`](dotnet/) | Preview, not yet on NuGet ([docs](https://brunoborges.github.io/copilot-sdk-integrations/dotnet/), [design](dotnet/docs/design.md)) |
| Polyglot (C# AppHost; .NET, Java, Node.js, Python services) | [Aspire](https://aspire.dev/) (AppHost runtime resource + .NET client integration, builds on `dotnet`) | [`aspire/`](aspire/) | Preview, not yet on NuGet ([docs](https://brunoborges.github.io/copilot-sdk-integrations/aspire/), [design](aspire/docs/design.md)) |
| Python | [Django](https://www.djangoproject.com/) | [`python/django/`](python/django/) | Preview, not yet on PyPI ([docs](https://brunoborges.github.io/copilot-sdk-integrations/django/), [design](python/django/DESIGN.md)) |
| Python | [FastAPI](https://fastapi.tiangolo.com/) | [`python/fastapi/`](python/fastapi/) | Preview, not yet on PyPI ([docs](https://brunoborges.github.io/copilot-sdk-integrations/fastapi/), [design](python/fastapi/DESIGN.md)) |
| Node.js / TypeScript | [NestJS](https://nestjs.com/), [Fastify](https://fastify.dev/), framework-agnostic Agent Core, [AI SDK](https://ai-sdk.dev/) adapter | [`nodejs/`](nodejs/) | In development ([NestJS docs](https://brunoborges.github.io/copilot-sdk-integrations/nestjs/), [Fastify docs](https://brunoborges.github.io/copilot-sdk-integrations/fastify/), [design](nodejs/DESIGN.md)) |

## Design goals

Every integration aims to provide the same capabilities, expressed in the target
framework's idioms:

- **Configuration**: typed settings bound from the framework's native configuration
  system.
- **Managed client**: a framework-managed Copilot client with lazy or eager startup
  and clean shutdown.
- **Declarative agents**: typed agent interfaces, prompt templates, and structured
  results.
- **Tools**: application services exposed as Copilot tools, keeping the framework's
  transactions, security, and other cross-cutting behavior.
- **MCP servers and BYOK providers**: configured declaratively per agent.
- **Safe defaults**: no implicit tool approval; explicit permission policies and
  tool allow/deny lists.
- **Per-user identity**: per-request GitHub authentication and context propagation.
- **Observability**: health checks, metrics, and tracing through the framework's
  standard mechanisms.
- **Testing**: an in-memory, scripted Copilot runtime for fast, deterministic tests.

## Repository layout

Each integration is a self-contained project in its own directory, with its own build,
README, and documentation. Java integrations live under [`java/`](java/) and share the
framework-neutral agent API and engine in [`java/core/`](java/core/), which every Java Maven build
includes as a reactor module. Shared repository files (license, CI workflows, and the
documentation landing page) live at the root.

```
.
├── java/
│   ├── core/        # Shared Java agent API and engine (copilot-core)
│   ├── spring-boot/ # Spring Boot starter (Java, Maven)
│   ├── quarkus/     # Quarkus extension (Java, Maven)
│   ├── micronaut/   # Micronaut integration (Java, Maven)
│   └── camel/       # Apache Camel component (Java, Maven)
├── dotnet/        # .NET integration for business apps (preview)
├── aspire/        # Aspire integration (.NET) (preview)
├── python/        # Python core (copilot-agent-core), Django and FastAPI integrations (preview)
├── nodejs/        # Node.js Agent Core, NestJS module, Fastify plugin, and AI SDK adapter (TypeScript; in development)
├── examples/      # Museum Exhibit and Web Accessibility workshop agents in eight frameworks
└── .github/       # CI workflows and documentation landing page
```

## Workshop examples

[`examples/`](examples/README.md) contains runnable **Museum Exhibit** and **Web
Accessibility** agents based on the
[Copilot SDK workshop](https://github.github.com/copilot-sdk-workshop/), using
the Spring Boot, Quarkus, Micronaut, NestJS, FastAPI, Fastify, Django, and .NET integrations.
Each example includes local build/run instructions and offline tests. The agents
demonstrate bounded source tools, explicit permissions, framework-managed runtime
lifecycle, and deterministic output validation.

## Contributing

Each integration's README describes how to build and test it. CI workflows are scoped
per directory, so changes to one integration only build that integration.

## License

[MIT](LICENSE)
