---
title: Documentation
---

# Copilot Agents for Aspire

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

<figure class="arch">
<svg viewBox="0 0 860 400" role="img" aria-labelledby="arch-title arch-desc" xmlns="http://www.w3.org/2000/svg">
<title id="arch-title">Aspire architecture for the Copilot runtime</title>
<desc id="arch-desc">The C# AppHost adds the Copilot runtime and a chat model. WithReference(chat) injects environment variables into .NET, Java, Node.js and Python services, which all connect to the same runtime over TCP with the connection token.</desc>
<defs>
<marker id="arch-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path class="arch-head" d="M0 0 10 5 0 10z"/></marker>
<marker id="arch-arrow-accent" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path class="arch-head-accent" d="M0 0 10 5 0 10z"/></marker>
</defs>
<rect class="arch-frame" x="20" y="20" width="770" height="150" rx="14"/>
<text class="arch-caption" x="40" y="46">Aspire AppHost · C#, .NET 10 SDK</text>
<path class="arch-link" d="M150 70V58H660V68" marker-end="url(#arch-arrow)"/>
<text class="arch-note" x="405" y="52" text-anchor="middle">starts and secures</text>
<rect class="arch-node" x="45" y="70" width="210" height="72" rx="10"/>
<text class="arch-code" x="150" y="101" text-anchor="middle">AddCopilot("copilot")</text>
<text class="arch-sub" x="150" y="123" text-anchor="middle">runtime resource</text>
<path class="arch-link" d="M255 106H288" marker-end="url(#arch-arrow)"/>
<rect class="arch-node" x="290" y="70" width="230" height="72" rx="10"/>
<text class="arch-code" x="405" y="101" text-anchor="middle">AddModel("chat", "gpt-5.4")</text>
<text class="arch-sub" x="405" y="123" text-anchor="middle">model resource</text>
<rect class="arch-runtime" x="555" y="70" width="210" height="72" rx="10"/>
<text class="arch-title" x="660" y="98" text-anchor="middle">Copilot runtime</text>
<text class="arch-sub" x="660" y="118" text-anchor="middle">headless · TCP</text>
<text class="arch-sub" x="660" y="133" text-anchor="middle">executable | container | existing</text>
<path class="arch-link" d="M405 142V200M105 200H705M105 200V229M305 200V229M505 200V229M705 200V229"/>
<path class="arch-link" d="M105 228V243" marker-end="url(#arch-arrow)"/>
<path class="arch-link" d="M305 228V243" marker-end="url(#arch-arrow)"/>
<path class="arch-link" d="M505 228V243" marker-end="url(#arch-arrow)"/>
<path class="arch-link" d="M705 228V243" marker-end="url(#arch-arrow)"/>
<rect class="arch-pill" x="120" y="187" width="570" height="26" rx="13"/>
<text class="arch-pill-text" x="405" y="205" text-anchor="middle">WithReference(chat) → CHAT_HOST · CHAT_PORT · CHAT_CONNECTIONTOKEN · CHAT_MODEL</text>
<rect class="arch-node" x="20" y="245" width="170" height="80" rx="10"/>
<text class="arch-title" x="105" y="276" text-anchor="middle">.NET</text>
<text class="arch-code-sm" x="105" y="300" text-anchor="middle">AddCopilotClient("chat")</text>
<rect class="arch-node" x="220" y="245" width="170" height="80" rx="10"/>
<text class="arch-title" x="305" y="276" text-anchor="middle">Java</text>
<text class="arch-sub" x="305" y="300" text-anchor="middle">Spring Boot or Quarkus</text>
<rect class="arch-node" x="420" y="245" width="170" height="80" rx="10"/>
<text class="arch-title" x="505" y="276" text-anchor="middle">Node.js</text>
<text class="arch-sub" x="505" y="300" text-anchor="middle">core, NestJS, or raw SDK</text>
<rect class="arch-node" x="620" y="245" width="170" height="80" rx="10"/>
<text class="arch-title" x="705" y="276" text-anchor="middle">Python</text>
<text class="arch-code-sm" x="705" y="300" text-anchor="middle">github-copilot-sdk</text>
<path class="arch-tcp" d="M105 325V360M305 325V360M505 325V360M705 325V360M105 360H830V106H767" marker-end="url(#arch-arrow-accent)"/>
<text class="arch-tcp-text" x="455" y="385" text-anchor="middle">TCP + connection token to the one shared runtime</text>
</svg>
</figure>

The runtime owns the GitHub sign-in and the model calls. Each service connects to it over
TCP with the connection token and runs its own sessions, tools and permission handling.
See [How it works](how-it-works.md).

## What runs in which language

| Part | Language | Package |
| --- | --- | --- |
| AppHost resources (`AddCopilot`, `AddModel`, `WithMcpServer`, `WithProvider`, …) | C# | `CopilotSdk.Aspire.Hosting` |
| .NET service client (`AddCopilotClient`) | C# | `CopilotSdk.Aspire` (optional, .NET only) |
| Java service | Java | [Spring Boot starter](https://brunoborges.github.io/copilot-sdk-integrations/spring-boot/) or [Quarkus extension](https://brunoborges.github.io/copilot-sdk-integrations/quarkus/) |
| Node.js service | TypeScript / JavaScript | [NestJS](https://brunoborges.github.io/copilot-sdk-integrations/nestjs/) or [Fastify](https://brunoborges.github.io/copilot-sdk-integrations/fastify/) integration, or `@github/copilot-sdk` |
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

- [Copilot Agents for .NET](https://brunoborges.github.io/copilot-sdk-integrations/dotnet/): the
  `IChatClient` integration that `CopilotSdk.Aspire` builds on.
- [Copilot Agents for Spring Boot](https://brunoborges.github.io/copilot-sdk-integrations/spring-boot/)
- [Copilot Agents for Quarkus](https://brunoborges.github.io/copilot-sdk-integrations/quarkus/)
- [Copilot Agents for NestJS](https://brunoborges.github.io/copilot-sdk-integrations/nestjs/)
- [Copilot Agents for Fastify](https://brunoborges.github.io/copilot-sdk-integrations/fastify/)
- [GitHub Copilot SDK](https://github.com/github/copilot-sdk)
- [Aspire documentation](https://aspire.dev/)
