---
title: Deployment
---

# Deployment

When you publish the AppHost (`aspire publish`, `aspire deploy`, or a manifest for another
deployment tool), the Copilot runtime is described like any other Aspire container resource. This
page covers what the integration emits. How the container is hosted depends on the publisher you
use.

> **Status:** publishing has been verified at the manifest level. End-to-end validation with
> specific deployment targets (for example `azd`) is still planned.

## The runtime in publish mode

In publish mode, `AddCopilot` always calls `RunAsContainer()`:

- The image is built from the Dockerfile that ships with the package, unless you choose one with
  `RunAsContainer(c => c.WithImage(...))`.
- The runtime listens on `0.0.0.0:3000` inside the container.
- The `tcp` endpoint is internal: other resources in the deployment can reach it, but it isn't
  exposed outside.
- The GitHub token and the connection token are secret parameters.

The manifest for a runtime named `copilot` contains, among other things:

```json
{
  "connectionString": "Endpoint=tcp://{copilot.bindings.tcp.host}:{copilot.bindings.tcp.port};ConnectionToken={copilot-connection-token.value}",
  "env": {
    "COPILOT_CONNECTION_TOKEN": "{copilot-connection-token.value}",
    "COPILOT_GITHUB_TOKEN": "{copilot-github-token.value}"
  }
}
```

`COPILOT_OTEL_ENABLED` is not set in publish mode. The runtime exports telemetry only when Aspire
supplies an OTLP endpoint.

## Secrets

| Parameter | Purpose | Supply it |
| --- | --- | --- |
| `{name}-github-token` | The GitHub identity of the runtime. Added unless you call `WithGitHubToken`, and not needed with `AsExisting`. | At deployment, like any secret parameter. Use a token for an account with Copilot access. |
| `{name}-connection-token` | The shared secret between the runtime and services. Generated unless you call `WithConnectionToken`. | Generated, or from your own parameter. |
| BYOK provider keys | Carried in the model connection string. | From the provider resource. |

Referencing services receive the connection token in their environment. Treat every service that
references the runtime as able to use its GitHub identity.

## Storage

The runtime stores sessions in `COPILOT_HOME`, which is `/home/copilot/.copilot` in the container.
Without a volume, sessions are lost when the container restarts. Keep them with a volume:

```cs
builder.AddCopilot("copilot").WithDataVolume();
```

## Using a runtime you deploy separately

If the runtime is deployed and operated outside the AppHost, point the resource at it. Nothing is
built or deployed for it:

```cs
var endpoint = builder.AddParameter("copilot-endpoint");
var token = builder.AddParameter("copilot-token", secret: true);
var copilot = builder.AddCopilot("copilot").AsExisting(endpoint, token);
```

`AsExisting` works in publish mode too and replaces the container. Services in other languages then
read the endpoint from the connection string, as described in
[Environment variable reference](environment-variables.md#existing-runtimes).

## Scaling and trust

- **One runtime per trust boundary.** Every service that holds the connection token uses the runtime's
  GitHub identity and can resume its sessions. Add separate runtimes for services that must not share
  them.
- **Multi-user services** should use per-user tokens at the session level, through the service's
  Copilot integration, rather than one shared identity for everyone.
- **Scale-out** of the runtime needs sticky session routing or shared session storage, because
  sessions live in the runtime's `COPILOT_HOME`.
