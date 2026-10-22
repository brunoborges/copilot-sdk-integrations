---
title: Documentation
---

# Copilot SDK for Aspire

This integration runs the GitHub Copilot runtime as a resource in an
[Aspire](https://aspire.dev/) AppHost. The AppHost starts the runtime, secures it with a
generated connection token, checks its health, and sends its telemetry to the Aspire
dashboard. Services that reference it get its connection details.

**The integration works with services in any language.** The AppHost is written in C#. The
services that use the runtime can be written in any language that Aspire can run: .NET, Java
(Spring Boot or Quarkus), Node.js, Python, or anything else that can read environment
variables. `WithReference(chat)` injects a connection string and language-neutral variables
such as `CHAT_HOST`, `CHAT_PORT`, `CHAT_CONNECTIONTOKEN` and `CHAT_MODEL`. Each service
passes them to its own Copilot SDK or framework integration.

> **Status:** preview. The packages are implemented and tested but not yet published to
> NuGet. Build them from the [repository](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/aspire).

```cs
var builder = DistributedApplication.CreateBuilder(args);

var copilot = builder.AddCopilot("copilot");          // headless Copilot runtime
var chat    = copilot.AddModel("chat", "gpt-5.4");

builder.AddProject<Projects.Api>("api").WithReference(chat).WaitFor(chat);       // .NET
builder.AddSpringBootApp("orders", "../orders").WithReference(chat).WaitFor(chat); // Java
builder.AddNodeApp("web", "../web", "server.js").WithReference(chat).WaitFor(chat); // Node.js
builder.AddUvicornApp("agent", "../agent", "main:app").WithReference(chat).WaitFor(chat); // Python

builder.Build().Run();
```

## Architecture

```text
                 Aspire AppHost (C#, .NET 10 SDK)
  ┌─────────────────────────────────────────────────────────────┐
  │  AddCopilot("copilot")  ──►  Copilot runtime (headless, TCP) │
  │        │                      executable | container | existing
  │        └─ AddModel("chat", "gpt-5.4")                        │
  │             WithReference(chat) injects connection details   │
  └───────┬──────────────┬──────────────┬──────────────┬────────┘
          │              │              │              │
          ▼              ▼              ▼              ▼
     .NET service   Spring Boot /   Node.js app    Python app
     AddCopilot-    Quarkus app     (core, NestJS, (github-copilot-sdk)
     Client("chat") (copilot.*      or raw SDK)
                    properties)
          │              │              │              │
          └──────────────┴──── TCP + connection token ─┘
                               to the shared runtime
```

The runtime owns the GitHub sign-in and the model calls. Each service connects to it over
TCP with the connection token and runs its own sessions, tools and permission handling.
See [How it works](how-it-works.md).

## What runs in which language

| Part | Language | Package |
| --- | --- | --- |
| AppHost resources (`AddCopilot`, `AddModel`, `WithMcpServer`, `WithProvider`, …) | C# | `CopilotSdk.Aspire.Hosting` |
| .NET service client (`AddCopilotClient`) | C# | `CopilotSdk.Aspire` (optional, .NET only) |
| Java service | Java | [Spring Boot starter](https://brunoborges.github.io/copilot-sdk-integrations/spring-boot/) or [Quarkus extension](https://brunoborges.github.io/copilot-sdk-integrations/quarkus/) |
| Node.js service | TypeScript / JavaScript | [Node.js integration](https://brunoborges.github.io/copilot-sdk-integrations/nodejs/) or `@github/copilot-sdk` |
| Python service | Python | `github-copilot-sdk` |

Only the AppHost needs .NET. The `CopilotSdk.Aspire` client package is a convenience for .NET
services. Other services read the injected environment variables through their usual
configuration.

## Requirements

| Component | Version |
| --- | --- |
| Aspire (`Aspire.Hosting`) | `13.6.3` |
| .NET SDK for the AppHost | `10.0` |
| `CopilotSdk.Aspire.Hosting` target framework | `net8.0` |
| `CopilotSdk.Aspire` target frameworks | `net10.0`, `net8.0` |
| Copilot SDK for .NET (`GitHub.Copilot.SDK`) | `1.0.26` |
| GitHub account | Copilot access, or a BYOK model provider |

Services in other languages use their own toolchains. The examples in this guide target
version `1.0.26` of the Copilot SDKs for Node.js and Python, the same version as the .NET SDK
that the Aspire packages use.

## Read by chapter

| Chapter | Covers |
| --- | --- |
| [Getting started](getting-started.md) | Build the packages, create an AppHost, add the runtime and a model, and connect a first service. |
| [How it works](how-it-works.md) | The runtime resource, model resources, what `WithReference` injects, and the trust boundary. |
| [Runtime resource](runtime.md) | Executable, container, and existing runtimes; the connection token, the GitHub token, storage, logging, and the runtime binary. |
| [Models and BYOK providers](models.md) | `AddModel`, reasoning effort, and `WithProvider` for OpenAI, Azure, and Anthropic endpoints. |
| [MCP servers](mcp-servers.md) | `WithMcpServer` and the settings it passes to services. |
| [.NET services](dotnet-services.md) | `AddCopilotClient`, keyed clients, client settings, and using core without the client package. |
| [Java (Spring Boot and Quarkus)](java-services.md) | `AddSpringBootApp`, `AddQuarkusApp`, and the `copilot.*` and `quarkus.copilot.*` properties. |
| [Node.js](nodejs-services.md) | `AddNodeApp`, `AddJavaScriptApp`, `createCopilot`, and the raw SDK. |
| [Python](python-services.md) | `AddPythonApp`, `AddUvicornApp`, and `CopilotClient` with `RuntimeConnection.for_uri`. |
| [Environment variable reference](environment-variables.md) | Every variable a referencing service receives, and how names are formed. |
| [Telemetry and health](telemetry-and-health.md) | OpenTelemetry export, content capture, and the AppHost and client health checks. |
| [Deployment](deployment.md) | Publish mode, the container image, secrets, and storage. |
| [Testing](testing.md) | Testing AppHost wiring and testing services without a runtime. |

## Related documentation

- [Copilot SDK for .NET](https://brunoborges.github.io/copilot-sdk-integrations/dotnet/): the
  `IChatClient` integration that `CopilotSdk.Aspire` builds on.
- [Copilot SDK for Spring Boot](https://brunoborges.github.io/copilot-sdk-integrations/spring-boot/)
- [Copilot SDK for Quarkus](https://brunoborges.github.io/copilot-sdk-integrations/quarkus/)
- [Copilot SDK for Node.js](https://brunoborges.github.io/copilot-sdk-integrations/nodejs/)
- [GitHub Copilot SDK](https://github.com/github/copilot-sdk)
- [Aspire documentation](https://aspire.dev/)
