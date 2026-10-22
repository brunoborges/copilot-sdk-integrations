---
title: Runtime resource
---

# Runtime resource

`AddCopilot` adds the Copilot runtime to the AppHost. This page covers how the runtime runs, how
it is secured, and where it keeps its data.

```cs
var copilot = builder.AddCopilot("copilot");           // random host port
var pinned  = builder.AddCopilot("copilot2", port: 5100); // fixed host port
```

| Parameter | Description |
| --- | --- |
| `name` | The resource name. It is also the connection name for `WithReference` and the prefix of the connection property variables (`COPILOT_HOST`, …). |
| `port` | The host port of the runtime's `tcp` endpoint. Random when omitted. |

## Run modes

| Mode | How to select it | What runs |
| --- | --- | --- |
| Executable | Default when you run the AppHost | The runtime binary on the developer machine, bound to `127.0.0.1`. |
| Container | `RunAsContainer()`, and always in publish mode | A container built from a Dockerfile that ships with the package, or an image you choose. |
| Existing | `AsExisting(endpoint, token)` | Nothing. Services connect to a runtime that something else runs. |

### Executable

The runtime starts as:

```text
<runtime> --headless --no-auto-update --host 127.0.0.1 --port <allocated port>
```

The binary is found in this order:

1. The `COPILOT_CLI_PATH` environment variable of the AppHost process.
2. The runtime bundled with the `GitHub.Copilot.SDK` package, copied to the AppHost output
   (`runtimes/{rid}/native/copilot-runtime`).
3. `copilot` on `PATH`.

Override it with `WithCliPath`. Relative paths resolve against the AppHost directory:

```cs
builder.AddCopilot("copilot").WithCliPath("/usr/local/bin/copilot");
```

### Container

```cs
builder.AddCopilot("copilot").RunAsContainer();
```

Without an image, the container is built from a Dockerfile that the package writes to
`obj/copilot-runtime` in the AppHost. It:

- starts from `debian:bookworm-slim`;
- downloads the Copilot CLI release that `GitHub.Copilot.SDK` pins, for `amd64` or `arm64`, and
  verifies it against the release's `SHA256SUMS.txt`;
- runs as the non-root user `copilot` (uid `10001`) with `COPILOT_HOME=/home/copilot/.copilot`;
- listens on port `3000` inside the container.

The image is named `copilot-runtime` and tagged with the CLI version. The endpoint is not
external. Configure the container with the callback; setting an image replaces the Dockerfile
build:

```cs
builder.AddCopilot("copilot").RunAsContainer(c => c
    .WithImage("myregistry.azurecr.io/copilot-runtime", "1.0.26")
    .WithLifetime(ContainerLifetime.Persistent));
```

A container can't use the developer's sign-in, so it needs a GitHub token. Unless you call
`WithGitHubToken`, `RunAsContainer` adds the secret parameter `{name}-github-token`, and the
dashboard prompts for it.

### Existing runtime

Point the resource at a runtime that Aspire doesn't start, such as a shared runtime in another
environment:

```cs
var endpoint = builder.AddParameter("copilot-endpoint");            // tcp://copilot.internal:3000
var token = builder.AddParameter("copilot-token", secret: true);

var copilot = builder.AddCopilot("copilot").AsExisting(endpoint, token);
var chat = copilot.AddModel("chat", "gpt-5.4");
```

Services get `Endpoint={endpoint};ConnectionToken={token}` (plus `;Model=…` for a model). Pass
the endpoint as `tcp://host:port`.

> **Note:** an existing runtime exposes only its connection string, not the `{NAME}_HOST`,
> `{NAME}_PORT`, `{NAME}_ENDPOINT`, and `{NAME}_CONNECTIONTOKEN` properties. A model of an
> existing runtime still exposes `{NAME}_MODEL`. Services in other languages read the endpoint and
> token from `ConnectionStrings__{name}` instead. See
> [Environment variable reference](environment-variables.md#existing-runtimes).

`RunAsContainer` and `WithConnectionToken` throw on an existing runtime. `AsExisting` also
removes the generated `{name}-github-token` parameter, because the existing runtime manages its own
sign-in.

## The connection token

The runtime refuses TCP clients that don't present its connection token. `AddCopilot` generates
one as the secret parameter `{name}-connection-token` and passes it to the runtime as
`COPILOT_CONNECTION_TOKEN`. Referencing services receive it in the connection string and in
`{NAME}_CONNECTIONTOKEN`.

Replace the generated token with your own parameter:

```cs
var token = builder.AddParameter("copilot-token", secret: true);
builder.AddCopilot("copilot").WithConnectionToken(token);
```

Like any Aspire parameter, a value can come from configuration, for example user secrets:

```bash
dotnet user-secrets set "Parameters:copilot-token" "<value>"
```

## The GitHub token

The GitHub token is the identity that the runtime signs in with. Only the runtime receives it, as
`COPILOT_GITHUB_TOKEN`. Referencing services never see it.

| Mode | Default sign-in |
| --- | --- |
| Executable | The user signed in to the Copilot CLI or the GitHub CLI on the developer machine. |
| Container | The `{name}-github-token` secret parameter, which you must supply. |
| Existing | Managed by the existing runtime. |

Supply a token explicitly:

```cs
var githubToken = builder.AddParameter("github-token", secret: true);
builder.AddCopilot("copilot").WithGitHubToken(githubToken);
```

`WithGitHubToken` removes the generated `{name}-github-token` parameter. If every model of the
runtime uses a [BYOK provider](models.md#bring-your-own-key-providers), the runtime doesn't need a
GitHub sign-in to be healthy.

## Storage

The runtime keeps sessions and settings in `COPILOT_HOME`.

| Mode | Default `COPILOT_HOME` | Change it with |
| --- | --- | --- |
| Executable | `{LocalApplicationData}/copilot-aspire/{hash of the AppHost directory}/{name}` | `WithHomeDirectory(path)` or `WithDataBindMount(path)` |
| Container | `/home/copilot/.copilot`, inside the container | `WithDataVolume()` or `WithDataBindMount(path)` |

The executable default is scoped to the AppHost and the resource, so Aspire runs don't mix with
your interactive CLI sessions, and it persists between runs.

```cs
builder.AddCopilot("copilot")
       .RunAsContainer()
       .WithDataVolume();                 // named volume, generated from the AppHost and resource names

builder.AddCopilot("copilot2")
       .WithDataBindMount("./copilot-data"); // bind mount in a container, COPILOT_HOME as an executable
```

`WithDataVolume` applies only in container mode. Relative paths resolve against the AppHost
directory.

## Logging

```cs
builder.AddCopilot("copilot").WithLogLevel(CopilotLogLevel.Debug);
```

`WithLogLevel` passes `--log-level` to the runtime. `CopilotLogLevel` comes from
`GitHub.Copilot.SDK` (namespace `GitHub.Copilot`) and has the members `None`, `Error`, `Warning`,
`Info`, `Debug`, and `All`. Runtime output appears in the resource's console logs on the dashboard.

## Reaching the runtime from containers

In executable mode the runtime binds to `127.0.0.1`. If the services that use it run as
containers, run the runtime as a container too, so that Aspire resolves its endpoint on the
container network.

## API summary

| Method | Applies to | Description |
| --- | --- | --- |
| `AddCopilot(name, port)` | AppHost builder | Adds the runtime. |
| `AddModel(name, model)` | Runtime | Adds a model resource. See [Models](models.md). |
| `RunAsContainer(configure)` | Runtime | Runs the runtime as a container. |
| `AsExisting(endpoint, connectionToken)` | Runtime | Points at a runtime that Aspire doesn't start. |
| `WithCliPath(path)` | Runtime | Sets the executable. Ignored in container and existing modes. |
| `WithHomeDirectory(path)` | Runtime | Sets `COPILOT_HOME` in executable mode. |
| `WithLogLevel(level)` | Runtime | Sets `--log-level`. |
| `WithGitHubToken(token)` | Runtime | Sets `COPILOT_GITHUB_TOKEN`. |
| `WithConnectionToken(token)` | Runtime | Replaces the generated connection token. |
| `WithDataVolume(name)` | Runtime | Persists `COPILOT_HOME` in a named volume (container mode). |
| `WithDataBindMount(source)` | Runtime | Persists `COPILOT_HOME` in a host directory. |
| `WithMcpServer(server, path, tools, endpointName)` | Runtime | Connects an MCP server. See [MCP servers](mcp-servers.md). |
| `WithMessageContentCapture(enabled)` | Runtime | Records prompts and responses in telemetry. See [Telemetry and health](telemetry-and-health.md). |
