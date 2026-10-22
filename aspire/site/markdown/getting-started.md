---
title: Getting started
---

# Getting started

This page creates an AppHost that runs the Copilot runtime, adds a model, and connects a
service to it. The AppHost is a C# project. The service can be written in any language;
this page uses a .NET service first and then shows where the other languages fit in.

## Prerequisites

- The .NET 10 SDK, for the AppHost.
- A GitHub account with Copilot access. When the runtime runs as a local executable, it uses
  the user who is signed in to the Copilot CLI (`copilot login`) or the GitHub CLI
  (`gh auth login`). To use a token instead, see [The GitHub token](runtime.md#the-github-token).
- The toolchain of each service you add: a JDK for Java, Node.js for JavaScript, or Python.
- A container runtime (Docker or Podman), only if you run the runtime as a container.

## Build the packages

The packages are not published to NuGet yet. Clone the repository and build them from
`aspire/`:

```bash
git clone https://github.com/brunoborges/copilot-sdk-integrations.git
cd copilot-sdk-integrations/aspire
dotnet build CopilotSdk.Aspire.slnx
dotnet test --solution CopilotSdk.Aspire.slnx
```

Two packages come out of the build:

| Package | Reference it from | Purpose |
| --- | --- | --- |
| `CopilotSdk.Aspire.Hosting` | The AppHost | `AddCopilot`, `AddModel`, and the other AppHost APIs. |
| `CopilotSdk.Aspire` | .NET services only | `AddCopilotClient`, which reads the connection string and adds health checks and telemetry. |

Until the packages are published, reference them with a project reference:

```xml
<ItemGroup>
  <ProjectReference Include="../copilot-sdk-integrations/aspire/src/CopilotSdk.Aspire.Hosting/CopilotSdk.Aspire.Hosting.csproj" />
</ItemGroup>
```

## Add the runtime and a model

In the AppHost's `AppHost.cs`:

```cs
var builder = DistributedApplication.CreateBuilder(args);

var copilot = builder.AddCopilot("copilot");
var chat = copilot.AddModel("chat", "gpt-5.4");

builder.AddProject<Projects.Api>("api")
       .WithReference(chat)
       .WaitFor(chat);

builder.Build().Run();
```

`AddCopilot` adds the runtime as a resource. It runs headless, listens on a TCP port on the
loopback interface, and requires a connection token that the AppHost generates. `AddModel`
adds a child resource that names the model. Referencing the model gives a service the runtime
connection plus the model name. `WaitFor(chat)` holds the service until the runtime is healthy,
which means it answers and is signed in.

Run the AppHost:

```bash
aspire run
```

The dashboard shows the `copilot` resource, its logs, its health, and its traces. If the
runtime is not signed in, the resource stays unhealthy with a message that tells you how to
sign in.

## Connect a .NET service

In the `Api` project, reference `CopilotSdk.Aspire` and add the client:

```cs
var builder = WebApplication.CreateBuilder(args);

builder.AddCopilotClient("chat", o => o.Instructions = "You help customers with orders.");

var app = builder.Build();

app.MapPost("/ask", async (IChatClient chat, string q) =>
    (await chat.GetResponseAsync(q)).Text);

app.Run();
```

`AddCopilotClient("chat")` reads `ConnectionStrings:chat`, which `WithReference(chat)` sets, and
registers an `IChatClient` from the [core .NET integration](https://brunoborges.github.io/copilot-sdk-integrations/dotnet/).
See [.NET services](dotnet-services.md).

## Connect services in other languages

The same `WithReference(chat)` works for any resource. Besides the connection string, it sets
language-neutral variables that other languages read directly:

| Variable | Example value |
| --- | --- |
| `CHAT_HOST` | `localhost` |
| `CHAT_PORT` | `5000` |
| `CHAT_ENDPOINT` | `tcp://localhost:5000` |
| `CHAT_CONNECTIONTOKEN` | The generated token |
| `CHAT_MODEL` | `gpt-5.4` |

Add the services with the Aspire hosting integration for their language, then follow the page
for that language:

```cs
builder.AddSpringBootApp("orders", "../orders")
       .WithReference(chat).WaitFor(chat);

builder.AddNodeApp("web", "../web", "server.js")
       .WithReference(chat).WaitFor(chat);

builder.AddUvicornApp("agent", "../agent", "main:app")
       .WithReference(chat).WaitFor(chat);
```

- [Java (Spring Boot and Quarkus)](java-services.md)
- [Node.js](nodejs-services.md)
- [Python](python-services.md)

Each service connects to the runtime with `host:port` and the connection token. It doesn't need
a GitHub token: the runtime owns sign-in.

## Next steps

- [How it works](how-it-works.md) explains the resources and what each reference injects.
- [Runtime resource](runtime.md) covers containers, existing runtimes, and tokens.
- [MCP servers](mcp-servers.md) and [Models and BYOK providers](models.md) add tools and other
  model endpoints.
