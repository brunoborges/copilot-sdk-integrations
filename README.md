# Copilot SDK Integrations

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
| .NET | Generic Host / ASP.NET Core ([Microsoft.Extensions.AI](https://learn.microsoft.com/dotnet/ai/microsoft-extensions-ai) `IChatClient`) | [`dotnet/`](dotnet/) | Preview, not yet on NuGet ([docs](https://brunoborges.github.io/copilot-sdk-integrations/dotnet/), [design](dotnet/docs/design.md)) |
| Polyglot (C# AppHost; .NET, Java, Node.js, Python services) | [Aspire](https://aspire.dev/) (AppHost runtime resource + .NET client integration, builds on `dotnet`) | [`aspire/`](aspire/) | Preview, not yet on NuGet ([docs](https://brunoborges.github.io/copilot-sdk-integrations/aspire/), [design](aspire/docs/design.md)) |
| Python | [Django](https://www.djangoproject.com/) | [`python/django/`](python/django/) | Preview, not yet on PyPI ([docs](https://brunoborges.github.io/copilot-sdk-integrations/django/), [design](python/django/DESIGN.md)) |
| Python | [FastAPI](https://fastapi.tiangolo.com/) | [`python/fastapi/`](python/fastapi/) | Preview, not yet on PyPI ([README](python/fastapi/README.md), [design](python/fastapi/DESIGN.md)) |
| Node.js / TypeScript | [NestJS](https://nestjs.com/), [Fastify](https://fastify.dev/), framework-agnostic core, [AI SDK](https://ai-sdk.dev/) adapter | [`nodejs/`](nodejs/) | In development ([DESIGN.md](nodejs/DESIGN.md)) |

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
framework-neutral agent API and engine in [`java/core/`](java/core/), which both Maven builds
include as a reactor module. Shared repository files (license, CI workflows, and the
documentation landing page) live at the root.

```
.
├── java/
│   ├── core/        # Shared Java agent API and engine (copilot-agent-core)
│   ├── spring-boot/ # Spring Boot starter (Java, Maven)
│   └── quarkus/     # Quarkus extension (Java, Maven)
├── dotnet/        # .NET integration for business apps (preview)
├── aspire/        # Aspire integration (.NET) (preview)
├── python/        # Python core (copilot-agent-core), Django and FastAPI integrations (preview)
├── nodejs/        # Node.js core, NestJS module, Fastify plugin, and AI SDK adapter (TypeScript; in development)
└── .github/       # CI workflows and documentation landing page
```

## Contributing

Each integration's README describes how to build and test it. CI workflows are scoped
per directory, so changes to one integration only build that integration.

## License

[MIT](LICENSE)
