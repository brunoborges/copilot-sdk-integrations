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
| Java | [Spring Boot](https://spring.io/projects/spring-boot) | [`spring-boot/`](spring-boot/) | Available (unreleased) |
| Java | [Quarkus](https://quarkus.io/) | `quarkus/` | Planned |
| .NET | Generic Host / ASP.NET Core ([Microsoft.Extensions.AI](https://learn.microsoft.com/dotnet/ai/microsoft-extensions-ai) `IChatClient`) | [`dotnet-core/`](dotnet-core/) | Design ([draft](dotnet-core/docs/design.md)) |
| .NET | [Aspire](https://aspire.dev/) (AppHost runtime resource + client integration, builds on `dotnet-core`) | [`dotnet-aspire/`](dotnet-aspire/) | Design ([draft](dotnet-aspire/docs/design.md)) |
| Python | [FastAPI](https://fastapi.tiangolo.com/), [Django](https://www.djangoproject.com/) | `python/` | Under consideration |
| Node.js | [NestJS](https://nestjs.com/), [Vercel AI SDK](https://ai-sdk.dev/) provider | `nodejs/` | Under consideration |

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

Each integration is a self-contained project in its own top-level directory, with its
own build, README, and documentation. Shared repository files (license, CI workflows,
and the documentation landing page) live at the root.

```
.
├── spring-boot/     # Spring Boot starter (Java, Maven)
├── dotnet-core/     # .NET integration for business apps, design phase
├── dotnet-aspire/   # Aspire integration (.NET), design phase
└── .github/         # CI workflows and documentation landing page
```

## Contributing

Each integration's README describes how to build and test it. CI workflows are scoped
per directory, so changes to one integration only build that integration.

## License

[MIT](LICENSE)
