# Copilot Testcontainers

Run the **real Copilot agent runtime harness**, isolated from the test host, and
connect the application's usual Copilot SDK over authenticated TCP. The portable
integration is the **image contract**, not a Java-only protocol or mock server.
Use any language's Testcontainers generic-container API; Java has an optional
typed helper.

This is a community integration with an unreleased Java artifact. The bundled
CLI/runtime image is published through this repository's GitHub Actions workflow
to **`ghcr.io/brunoborges/copilot-sdk-integrations/copilot-runtime`**. It is public
and multi-arch (`linux/amd64`, `linux/arm64`); local image builds remain supported.

## Architecture and scope

```text
Application under test + Copilot SDK
         | JSON-RPC over authenticated TCP (random mapped host port)
         v
Testcontainers -> Copilot runtime container -> Copilot service OR BYOK provider
```

The image packages the full released CLI and runtime, not an emulation of SDK behavior.
Keep fast in-memory framework tests for business logic; use this integration for
transport, sessions, tools, permissions, streaming, and real harness behavior.
A deterministic BYOK provider can replace the model without replacing the harness.
Real-model assertions are opt-in application tests and can consume paid usage.

Each test container has its own writable home and workspace, no host credential
mounts, and a random connection secret supplied by the test. Java's helper depends
only on Testcontainers, so applications choose their own SDK and framework
versions. Existing Spring Boot `CopilotContainer` remains supported; both helpers
can use this image. There is deliberately no dependency between the two helpers.

## Build and run

From the repository root:

```sh
docker build -t copilot-runtime:1.0.105 testcontainers/image
mvn -f testcontainers/pom.xml install
mvn -f testcontainers/pom.xml -Pcontainer-tests verify
```

Docker must be running; Java tests require Java 21+ and Maven 3.9+. Normal
`mvn -f testcontainers/pom.xml verify` runs Docker-free tests. The container profile
requires Docker and the image and does not skip on missing prerequisites. Override
an image under test with `-Dcopilot.test.image=your-registry/copilot-runtime:tag`.

The Dockerfile pins CLI `1.0.105`, supports Linux `amd64` and `arm64`, and verifies
both release archives against their published SHA256 checksums. It uses the runtime's
native `--headless --host 0.0.0.0` TCP listener, not a shell proxy. Override the
release using `--build-arg COPILOT_VERSION=...`, tag the image accordingly, and
rerun compatibility tests. Do not rely on a floating `latest` tag. The Java
reference tests use SDK `1.0.24`; other SDK/runtime pairs must have compatible
protocol versions.

To skip the local build, replace the local image name in every language's
recipe below with
`ghcr.io/brunoborges/copilot-sdk-integrations/copilot-runtime:<cli-version>`.
The image contains the CLI at build time and requires no runtime download.

## Daily GitHub Packages publication

The `Publish Copilot CLI image` workflow runs daily at **06:23 UTC**, on relevant
pushes to `main`, and through **Actions > Publish Copilot CLI image > Run workflow**.
Scheduled workflows run from the default branch and GitHub may delay scheduled
starts. Publication is restricted to that branch, including manual dispatches.

Each run resolves the newest stable release from `github/copilot-cli`, validates
the release number, and uses it as `COPILOT_VERSION`. Separate native Linux amd64
and arm64 jobs build with `--pull`, verify the full CLI and license, and run the
Java SDK container tests. Only successful jobs push their run-specific images.
The final job creates multi-platform manifests only after **both** architectures
pass; a failing upstream release leaves the existing public tags unchanged.

| Tag | Meaning |
| --- | --- |
| `<cli-version>` | Most recent successful build of that CLI release. |
| `<cli-version>-<YYYYMMDD>` | Successful build for that UTC date (reruns that day replace it). |
| `latest` | Most recent fully tested release/build. |
| `build-<run-id>-<attempt>-<arch>` | Run-scoped architecture image used to assemble the tested manifest. |

Even when no new CLI is released, daily builds refresh the Debian base image and
OS packages. Version tags are therefore **not immutable**: use a digest from
`docker buildx imagetools inspect <image>:<tag>` for reproducible application tests.
The Java SDK compatibility check is deliberate: if a newer CLI requires a newer
SDK, update the reference SDK and tests rather than publishing an untested image.

### Repository linkage

Run the workflow manually to publish immediately, or wait for the next daily
run. The job-scoped Actions token
has `packages: write`; no personal token, Copilot token, or extra registry secret
is used. The workflow logs into `ghcr.io`, and the image's
`org.opencontainers.image.source` label links the package to this repository so
it appears under its **Packages** section.

GHCR packages may initially be private even for a public repository. If anonymous
pulls are intended, a repository/package administrator must check the package's
**Package settings > Change visibility** and select public. The workflow does
not silently change package visibility or repository permissions. Existing
packages must grant this repository Actions access under their package settings.

The workflow publishes under the repository that runs it; in a fork the image
path becomes `ghcr.io/<owner>/<repository>/copilot-runtime`.

### Use the full CLI

The default entrypoint runs the SDK harness. Override it to run the bundled CLI:

```sh
docker run --rm --entrypoint copilot \
  ghcr.io/brunoborges/copilot-sdk-integrations/copilot-runtime:latest --version
```

Running CLI commands directly does not require the image's server connection-token
setting, but prompts still require explicit authentication. The CLI binaries are
unmodified and the upstream CLI license, copyright, and attribution notices are
retained under `/opt/copilot/`, including `/opt/copilot/LICENSE.md`. This
repository's MIT license applies to the integration, not the bundled CLI.

## Language-neutral image contract

| Setting | Contract |
| --- | --- |
| `COPILOT_PORT` | Internal TCP port, default `4321`, integer `1024..65535`. Expose the same port through Testcontainers. |
| `COPILOT_CONNECTION_TOKEN` | Required non-empty shared connection secret. Generate a fresh random value per container; supply the identical value to the SDK. |
| `COPILOT_GITHUB_TOKEN` | Optional runtime GitHub authentication. Not automatically copied from the host. A session BYOK provider needs no GitHub token. |
| Entrypoint | Runs the native harness as PID 1; receives container stop signals directly. Extra command arguments are appended to runtime arguments. |
| User | Non-root UID `10001`, home `/home/copilot`, working directory `/home/copilot/workspace`. |
| Readiness | Wait for the mapped TCP port, then start the SDK (which authenticates and checks the protocol). An open port alone does not prove SDK compatibility. |
| Transport | SDK JSON-RPC over TCP, **not an HTTP API**. Use `host:mappedPort` and the SDK connection-token option. |
| Lifetime | Fresh disposable container per test/class; let Testcontainers stop and remove it. No persistent volumes by default. |

The `COPILOT_PORT` environment variable is an image-entrypoint convention, not a
Copilot SDK configuration option. Custom images must implement this same contract
to work with the Java helper. Get the host and mapped port from Testcontainers,
never assume `localhost` or bind a fixed host port. For applications in another
container, attach both to one Testcontainers network and use a network alias plus
the **internal** port, not the mapped host port.

The helper's `getCliUrl()` returns `http://host:port` for Java/framework compatibility;
that legacy SDK syntax does not turn the connection into HTTP. Do not probe with
`curl`, use HTTP waits, or put it behind an HTTP reverse proxy.

## Java reference

Install the unreleased module locally, then use test-scoped
`com.microsoft:copilot-testcontainers:0.1.0-SNAPSHOT` and add
`com.github:copilot-sdk-java:1.0.24` separately.

```java
import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;
import com.microsoft.copilot.integrations.testcontainers.CopilotContainer;

try (var runtime = new CopilotContainer("copilot-runtime:1.0.105")) {
    runtime.start();
    try (var client = new CopilotClient(new CopilotClientOptions()
            .setCliUrl(runtime.getEndpoint())
            .setTcpConnectionToken(runtime.getTcpConnectionToken()))) {
        client.start().join();
        System.out.println(client.ping("test").join().message());
        // Pass client to your app's test configuration.
    }
}
```

`withCopilotPort(int)`, `withConnectionToken(String)`, and `withGitHubToken(String)`
validate their inputs. A random connection secret is generated by default.
Do not call `withGitHubToken` unless you have an actual token; BYOK tests omit it.
Generic Testcontainers methods remain available for networks, files, environment
variables, and startup timeouts. Do not override the image's port/token environment
directly when using the helper: its typed settings are authoritative at startup.

For JUnit-managed lifecycle, add Testcontainers' `testcontainers-junit-jupiter`
dependency and use `@Testcontainers` / `@Container` in the normal way.

### Wire framework applications

Pass endpoint and token through the framework's ordinary test configuration:

| Framework | Endpoint and connection-secret settings |
| --- | --- |
| Spring Boot | `copilot.cli-url`, `copilot.tcp-connection-token`; or the existing Spring helper with `@ServiceConnection`. |
| Quarkus | `quarkus.copilot.cli-url`, `quarkus.copilot.tcp-connection-token` via a test resource. |
| Micronaut | `micronaut.copilot.cli-url`, `micronaut.copilot.tcp-connection-token` via test properties. |
| Other frameworks | Inject a client configured with the SDK's external-runtime endpoint and TCP connection token. |

Use the framework's external connection mode if one is selected explicitly.
Do not also configure a local CLI executable or inherit local-login settings.
For the Spring-specific `@ServiceConnection` container use
`com.microsoft.copilot.integrations.springboot.test.testcontainers.CopilotContainer`;
the standalone helper intentionally does not auto-register Spring services.

## Other language ecosystems

These are equivalent generic-container recipes, not separately published language
modules. Install each ecosystem's Testcontainers package and, where available,
the relevant Copilot SDK. These recipes follow the common image contract;
the automated end-to-end reference suite is Java.

### Node.js / TypeScript

```typescript
import { randomUUID } from "node:crypto";
import { GenericContainer, Wait } from "testcontainers";
import { CopilotClient } from "@github/copilot-sdk";

const token = randomUUID();
const runtime = await new GenericContainer("copilot-runtime:1.0.105")
  .withEnvironment({ COPILOT_CONNECTION_TOKEN: token })
  .withExposedPorts(4321)
  .withWaitStrategy(Wait.forListeningPorts())
  .start();
try {
  const client = new CopilotClient({
    cliUrl: `${runtime.getHost()}:${runtime.getMappedPort(4321)}`,
    tcpConnectionToken: token,
  });
  try {
    await client.start();
    console.log(await client.ping("test"));
  } finally {
    await client.stop();
  }
} finally {
  await runtime.stop();
}
```

### Python

```python
import asyncio
import uuid
from copilot import CopilotClient
from testcontainers.core.container import DockerContainer
from testcontainers.core.wait_strategies import PortWaitStrategy

async def test_runtime():
    token = str(uuid.uuid4())
    with (DockerContainer("copilot-runtime:1.0.105")
          .with_env("COPILOT_CONNECTION_TOKEN", token)
          .with_exposed_ports(4321)
          .waiting_for(PortWaitStrategy(4321))) as runtime:
        client = CopilotClient({
            "cli_url": f"{runtime.get_container_host_ip()}:{runtime.get_exposed_port(4321)}",
            "tcp_connection_token": token,
        })
        try:
            await client.start()
            print(await client.ping("test"))
        finally:
            await client.stop()

asyncio.run(test_runtime())
```

### .NET

```csharp
using DotNet.Testcontainers.Builders;
using GitHub.Copilot.SDK;

var token = Guid.NewGuid().ToString();
await using var runtime = new ContainerBuilder()
    .WithImage("copilot-runtime:1.0.105")
    .WithEnvironment("COPILOT_CONNECTION_TOKEN", token)
    .WithPortBinding(4321, true)
    .WithWaitStrategy(Wait.ForUnixContainer().UntilInternalTcpPortIsAvailable(4321))
    .Build();
await runtime.StartAsync();
await using var client = new CopilotClient(new CopilotClientOptions {
    CliUrl = $"{runtime.Hostname}:{runtime.GetMappedPublicPort(4321)}",
    TcpConnectionToken = token,
});
await client.StartAsync();
// Inject client into the application under test.
```

### Go and other Testcontainers implementations

```go
tokenBytes := make([]byte, 32)
if _, err := rand.Read(tokenBytes); err != nil { t.Fatal(err) }
token := hex.EncodeToString(tokenBytes)
runtime, err := testcontainers.GenericContainer(ctx,
    testcontainers.GenericContainerRequest{
        ContainerRequest: testcontainers.ContainerRequest{
            Image: "copilot-runtime:1.0.105",
            ExposedPorts: []string{"4321/tcp"},
            Env: map[string]string{"COPILOT_CONNECTION_TOKEN": token},
            WaitingFor: wait.ForListeningPort("4321/tcp").SkipInternalCheck(),
        },
        Started: true,
    })
if err != nil { t.Fatal(err) }
t.Cleanup(func() {
    if err := runtime.Terminate(ctx); err != nil { t.Error(err) }
})
host, err := runtime.Host(ctx)
if err != nil { t.Fatal(err) }
port, err := runtime.MappedPort(ctx, "4321/tcp")
if err != nil { t.Fatal(err) }
endpoint := net.JoinHostPort(host, port.Port())
// Configure the Copilot Go SDK's CLI URL and TCP connection token,
// or pass endpoint and token to the application being tested.
```

Go imports are `crypto/rand`, `encoding/hex`, `net`,
`github.com/testcontainers/testcontainers-go`, and
`github.com/testcontainers/testcontainers-go/wait`; `t` is your `*testing.T`
and `ctx` your context.

Rust, Ruby, PHP, and other Testcontainers implementations use the identical
recipe: set a random connection token, expose TCP `4321`, wait for the port,
retrieve the mapped address, pass address and secret to the app, and clean up.
An ecosystem without a Copilot SDK can still orchestrate the runtime and a
separate SDK-based application container; the image does not invent an SDK
for unsupported languages.

## Deterministic model tests and live tests

The executable Java `CopilotContainerIT` starts a local OpenAI-compatible SSE
endpoint, exposes it with `Testcontainers.exposeHostPorts`, and points a real SDK
session's `ProviderConfig` at `http://host.testcontainers.internal:<port>/v1`.
It sets `type=openai`, `wireApi=completions`, an explicit test model and dummy
provider key, disables built-in tools, and rejects permission requests.
The test checks the exact assistant reply and verifies that the provider was
called. This validates a full application-to-SDK-to-harness-to-provider round trip
without GitHub authentication or model nondeterminism.

For real Copilot model tests, inject a Copilot-authorized GitHub token into the
runtime through `COPILOT_GITHUB_TOKEN` (Java: `withGitHubToken`). For real BYOK
tests, put provider credentials in session configuration, not the image. Never
pass GitHub credentials through the SDK's local-process auth options when using
an external runtime.

Application tools registered through the SDK execute in the **application/test
process**, not the runtime container. Runtime built-in tools and local MCP
processes execute **inside** the runtime container; this minimal image includes
Git but not Node.js, Python, Java, or your app's files. Extend it explicitly when
tests need more tools. Containerization does not sandbox SDK callback code.

## Security, isolation, and troubleshooting

- The TCP connection secret authenticates clients but does not encrypt traffic.
  Keep the runtime on trusted test networks; never expose it publicly.
- No host workspace, SSH agent, Docker socket, or credential directory is mounted.
  Copy only explicit fixtures; use temporary writable test directories.
- Do not log connection tokens, GitHub tokens, provider keys, or whole SDK options.
  Docker environment inspection can reveal tokens; use short-lived test secrets.
- Do not enable reusable containers with production secrets. Use separate
  containers for independent tests; share within a class only deliberately.
- An image cannot bind? Check `COPILOT_PORT` range and the matching exposed port.
  Custom port changes do not change the Dockerfile's informational `EXPOSE`.
- SDK startup fails? Check both token values, runtime/SDK protocol compatibility,
  and that the SDK is configured for external TCP rather than local stdio.
- Provider/MCP connection fails? `localhost` inside the runtime is not the test
  host. Use a shared network alias or Testcontainers' host-port forwarding.
- Missing image? Build it on the Docker daemon used by the tests or push/pull
  from your registry. A local image on another daemon is not available.

The dedicated pull-request CI workflow builds the real image and runs the Java
unit and container tests on Linux amd64 and arm64 without secrets. The separate
daily publisher also runs those tests before publishing to GHCR. Documentation
is published alongside the other integration guides.
