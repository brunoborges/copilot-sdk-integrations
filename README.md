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

Framework integrations for the [Microsoft Copilot SDKs](https://github.com/microsoft/copilot-sdk).

The Copilot SDKs give you a low-level client for the Copilot runtime. This repository
adds idiomatic integration layers for popular application frameworks, so the client,
agents, tools, and sessions feel native to each framework: configuration, dependency
injection, lifecycle management, security context, observability, and testing.

📖 **[Documentation](https://brunoborges.github.io/copilot-sdk-integrations/)**

## Integrations

| Language | Framework / library | Directory | Status |
| --- | --- | --- | --- |
| Java | [Spring Boot](https://spring.io/projects/spring-boot) | [`java/spring-boot/`](java/spring-boot/) | Available, GitHub Packages (Maven) |
| Java | [Quarkus](https://quarkus.io/) | [`java/quarkus/`](java/quarkus/) | Available, GitHub Packages (Maven) |
| Java | [Micronaut](https://micronaut.io/) | [`java/micronaut/`](java/micronaut/) | Available, GitHub Packages (Maven) |
| Java | [Apache Camel](https://camel.apache.org/) | [`java/camel/`](java/camel/) | Available, GitHub Packages (Maven) ([docs](https://brunoborges.github.io/copilot-sdk-integrations/camel/)) |
| .NET | Generic Host / ASP.NET Core ([Microsoft.Extensions.AI](https://learn.microsoft.com/dotnet/ai/microsoft-extensions-ai) `IChatClient`) | [`dotnet/`](dotnet/) | Preview, GitHub Packages (NuGet) ([docs](https://brunoborges.github.io/copilot-sdk-integrations/dotnet/), [design](dotnet/docs/design.md)) |
| Polyglot (C# AppHost; .NET, Java, Node.js, Python services) | [Aspire](https://aspire.dev/) (AppHost runtime resource + .NET client integration, builds on `dotnet`) | [`aspire/`](aspire/) | Preview, GitHub Packages (NuGet) ([docs](https://brunoborges.github.io/copilot-sdk-integrations/aspire/), [design](aspire/docs/design.md)) |
| Polyglot (Java reference module) | [Testcontainers](https://testcontainers.com/) (shared CLI/runtime image and Java helper) | [`testcontainers/`](testcontainers/) | Preview, daily GHCR image; GitHub Packages (Maven) ([docs](https://brunoborges.github.io/copilot-sdk-integrations/testcontainers/)) |
| Python | [Django](https://www.djangoproject.com/) | [`python/django/`](python/django/) | Preview, GitHub Release assets, not on PyPI ([docs](https://brunoborges.github.io/copilot-sdk-integrations/django/), [design](python/django/DESIGN.md)) |
| Python | [FastAPI](https://fastapi.tiangolo.com/) | [`python/fastapi/`](python/fastapi/) | Preview, GitHub Release assets, not on PyPI ([docs](https://brunoborges.github.io/copilot-sdk-integrations/fastapi/), [design](python/fastapi/DESIGN.md)) |
| Node.js / TypeScript | [NestJS](https://nestjs.com/), [Fastify](https://fastify.dev/), framework-agnostic Agent Core, [AI SDK](https://ai-sdk.dev/) adapter | [`nodejs/`](nodejs/) | Preview, GitHub Packages (npm) ([NestJS docs](https://brunoborges.github.io/copilot-sdk-integrations/nestjs/), [Fastify docs](https://brunoborges.github.io/copilot-sdk-integrations/fastify/), [design](nodejs/DESIGN.md)) |

## Installing released packages

Every integration is released together under one version from a `vX.Y.Z` tag; see
[Releases](https://github.com/brunoborges/copilot-sdk-integrations/releases) for the
latest version (the examples below use `0.1.0`). Java, .NET, and Node.js packages are
published to [GitHub Packages](https://github.com/brunoborges?tab=packages&repo_name=copilot-sdk-integrations).
Python packages are attached to each GitHub Release, because GitHub Packages has no
Python registry.

GitHub Packages requires authentication even for public packages. Create a
[personal access token (classic)](https://github.com/settings/tokens/new?scopes=read:packages)
with the `read:packages` scope; the examples read it from the `GITHUB_TOKEN`
environment variable. In GitHub Actions, the workflow's `GITHUB_TOKEN` works if it has
`packages: read` permission.

### Java (Maven or Gradle)

Add the repository to your `pom.xml`, alongside Maven Central:

```xml
<repositories>
  <repository>
    <id>copilot-sdk-integrations</id>
    <url>https://maven.pkg.github.com/brunoborges/copilot-sdk-integrations</url>
  </repository>
</repositories>
```

Add matching credentials to `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>copilot-sdk-integrations</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

With Gradle (Kotlin DSL):

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/brunoborges/copilot-sdk-integrations")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}
```

All Java artifacts use the `com.microsoft` group ID:

| Integration | Artifact | Test support |
| --- | --- | --- |
| Spring Boot | `copilot-spring-boot-starter` | `copilot-spring-boot-test` |
| Quarkus | `copilot-quarkus` | `copilot-quarkus-test` |
| Micronaut | `copilot-micronaut` (plus `copilot-micronaut-processor` as an annotation processor) | `copilot-micronaut-test` |
| Apache Camel | `copilot-camel` | `copilot-camel-test` |
| Testcontainers | `copilot-testcontainers` | |

`copilot-core`, the shared Java agent API and engine, comes in transitively.

### .NET (NuGet)

```shell
dotnet nuget add source https://nuget.pkg.github.com/brunoborges/index.json \
  --name copilot-sdk-integrations --username YOUR_GITHUB_USERNAME \
  --password "$GITHUB_TOKEN" --store-password-in-clear-text
dotnet add package CopilotSdk.Extensions.Hosting --version 0.1.0
```

Aspire apps add `CopilotSdk.Aspire.Hosting` to the AppHost and `CopilotSdk.Aspire` to
.NET services.

### Node.js (npm)

Point the `@brunoborges` scope at GitHub Packages in your project's `.npmrc`:

```ini
@brunoborges:registry=https://npm.pkg.github.com
//npm.pkg.github.com/:_authToken=${GITHUB_TOKEN}
```

Then install the package for your framework: `@brunoborges/copilot-nestjs`,
`@brunoborges/copilot-fastify`, `@brunoborges/copilot-ai` (AI SDK adapter), or
`@brunoborges/copilot-agent-core`:

```shell
npm install @brunoborges/copilot-nestjs
```

### Python

Install from the release's assets; no token is needed:

```shell
pip install copilot-fastapi==0.1.0 \
  --find-links https://github.com/brunoborges/copilot-sdk-integrations/releases/expanded_assets/v0.1.0
```

Use `copilot-django` for Django. `copilot-agent-core` is resolved from the same
release. `uv pip install` accepts the same `--find-links` option.

### Releasing

Maintainers release by pushing a tag from `main`, for example
`git tag v0.1.0 && git push origin v0.1.0`. Use `-alpha.N`, `-beta.N`, or `-rc.N`
suffixes for prereleases. The [Release workflow](.github/workflows/release.yml) stamps
the version into every build, runs the tests, publishes Java, .NET, and Node.js
packages to GitHub Packages, and creates the GitHub Release with the Python wheels and
source distributions. Source files keep their development versions. Published versions
can't be overwritten, so fix a bad release with a new version. After the first release,
make the npm and NuGet packages public in each package's settings; Maven packages
inherit the repository's visibility.

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
- **Testing**: an in-memory, scripted Copilot runtime for fast, deterministic tests,
  plus a shared Testcontainers image for tests against the real runtime harness.

## Repository layout

Each integration is a self-contained project in its own directory, with its own build,
README, and documentation. Java framework integrations live under [`java/`](java/) and share the
framework-neutral agent API and engine in [`java/core/`](java/core/), which every Java Maven build
includes as a reactor module. Shared repository files (license, CI workflows, and the
documentation landing page) live at the root.
The polyglot [`testcontainers/`](testcontainers/) integration includes its standalone
Java helper alongside the language-neutral runtime image.

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
├── testcontainers/ # Polyglot runtime image and standalone Java Testcontainers helper (preview)
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
