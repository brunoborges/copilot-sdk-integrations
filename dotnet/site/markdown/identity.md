---
title: Runtime and identity
---

# Runtime and identity

The Copilot SDK talks to a Copilot runtime process. By default, the SDK starts its bundled
runtime for your app; you can also point at a shared runtime. Each turn runs under an identity:
the signed-in developer, a service token, the end user's own token, or your own model
deployment (BYOK).

**In this chapter**

- [Identity modes](#identity-modes)
- [Service token resolution](#service-token-resolution)
- [Per-user identity](#per-user-identity)
- [Shared runtimes](#shared-runtimes)
- [Client mode and runtime home](#client-mode-and-runtime-home)
- [Lifecycle](#lifecycle)

## Identity modes

| Mode | How |
| --- | --- |
| **Signed-in user** (local development) | Nothing to configure: the SDK-managed runtime uses the user signed in to the [Copilot CLI](https://docs.github.com/copilot/how-tos/set-up/install-copilot-cli). |
| **Service identity** (workers, internal tools) | `Copilot:GitHubToken`, or the `COPILOT_GITHUB_TOKEN` / `GH_TOKEN` / `GITHUB_TOKEN` environment variable (SDK-managed runtime only). |
| **Per-user identity** (usage billed to and scoped by the signed-in user) | `o.GitHubTokenProvider`, called at the start of each turn. |
| **BYOK** (your own model deployment) | `Copilot:Provider`. See [BYOK providers](configuration.md#byok-providers). |

## Service token resolution

For the SDK-managed runtime, the first non-empty value wins:

1. `Copilot:GitHubToken`
2. `COPILOT_GITHUB_TOKEN`
3. `GH_TOKEN`
4. `GITHUB_TOKEN`
5. the user signed in to the Copilot CLI

The environment variables are read through `IConfiguration` and passed to the runtime
explicitly, because the SDK-managed runtime doesn't pick them up on its own. Keep the token in
a secret store, user secrets, or the environment:

```bash
dotnet user-secrets set Copilot:GitHubToken <token>
# or
export Copilot__GitHubToken=<token>
```

When `Endpoint` is set, these are ignored: a shared runtime manages its own sign-in, and
setting `GitHubToken` together with `Endpoint` fails [validation](configuration.md#validation).

## Per-user identity

To run each turn as the end user, so usage is billed to and scoped by that user, supply a token
per turn:

```cs
builder.Services.AddHttpContextAccessor();
builder.Services.AddCopilot(o => o.GitHubTokenProvider = (services, ct) =>
    services.GetRequiredService<IHttpContextAccessor>().HttpContext!.GetTokenAsync("access_token"));
```

- `GitHubTokenProvider` is a `Func<IServiceProvider, CancellationToken, Task<string?>>`.
- It runs on the caller's thread at the start of each turn, so `HttpContext` is available.
- The token is handed to the runtime with a 300-second expiry; refreshes call the provider
  again under the caller's `ExecutionContext`.
- If it returns `null`, the call throws `InvalidOperationException` instead of falling back to
  the service identity.
- It is per agent; named agents inherit the default agent's provider.
- It works with a shared runtime (`Endpoint`) too.
- With a provider configured, the [health check](observability.md#health-checks) doesn't report
  Degraded when the runtime itself isn't signed in.

## Shared runtimes

Set `Copilot:Endpoint` to connect to an already-running runtime instead of starting one:

```json
{
  "Copilot": {
    "Endpoint": "tcp://copilot.internal:5000",
    "ConnectionToken": "<shared secret>"
  }
}
```

`Endpoint` accepts `tcp://host:port`, `http(s)://host:port`, `host:port`, or a loopback port
number. A non-loopback endpoint requires `ConnectionToken`.

The same values can come from a [connection string](configuration.md#connection-strings),
which is how an Aspire AppHost hands its runtime to your service. See the
[Copilot for Aspire](https://brunoborges.github.io/copilot-sdk-integrations/aspire/)
documentation.

## In-process runtime

By default the SDK starts its bundled runtime as a child process. Set `Copilot:InProcess` to
`true` to host it inside your process instead, with no child process or local port:

```json
{ "Copilot": { "InProcess": true } }
```

In-process hosting is experimental in the SDK (diagnostic `GHCP001`; set it in code inside
`#pragma warning disable GHCP001`, or bind it from configuration). The token resolution and
runtime home below apply unchanged. Runtime OpenTelemetry export is not available in-process,
so `OTEL_EXPORTER_OTLP_ENDPOINT` is ignored for the runtime (MEAI telemetry still works).
`InProcess` cannot be combined with `Endpoint`, and a connection string's endpoint is ignored
when it is set. The SDK's `COPILOT_SDK_DEFAULT_CONNECTION=inprocess` environment variable is
also honored.

## Client mode and runtime home

The client runs with `Mode = CopilotClientMode.Empty`: built-in tools, skills, plugins,
and the CLI's custom instructions are off by design, and only your tools and MCP servers are
offered.

Unless `Endpoint` or `BaseDirectory` is set, the SDK-managed runtime uses a per-app home under
the local application data folder, `copilot-sdk/{app}-{hash}`, where the hash is derived from
the application name and content root. Its state survives restarts and doesn't mix with your
interactive Copilot CLI sessions; the signed-in user still works locally. Override either with
`ConfigureClient`:

```cs
builder.Services.AddCopilot(o => o.ConfigureClient(client => client.BaseDirectory = "/var/copilot"));
```

## Lifecycle

- **Start:** the client starts on first use. Set `Copilot:EagerStart` to `true` to start it with
  the host instead.
- **Stop:** the client stops with the host, force-stopping if shutdown times out.
- **One client per app:** every agent shares one `CopilotClient` and one runtime, configured
  from the default agent.
- **Logging:** the SDK logs through the host's `ILoggerFactory` under
  `GitHub.Copilot.CopilotClient`.
