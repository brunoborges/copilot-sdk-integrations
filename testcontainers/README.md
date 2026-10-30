# Copilot Testcontainers

Run the real Copilot agent runtime harness in a disposable container while
testing applications that use a Copilot SDK. The image is language-neutral:
every Testcontainers ecosystem can use its generic container API. A standalone
Java helper provides typed configuration and connection details without Spring
or a transitive Copilot SDK dependency.

**Preview.** The Java artifact is unreleased. The
[`Publish Copilot CLI image` workflow](../.github/workflows/publish-copilot-runtime.yml)
publishes the bundled CLI and runtime harness to
`ghcr.io/brunoborges/copilot-sdk-integrations/copilot-runtime` daily at **06:23 UTC**,
on relevant pushes to `main`, and on manual dispatch. The package is public
and multi-arch (`linux/amd64`, `linux/arm64`). This is a community
integration, not an official GitHub runtime image.

## Quick start

From the repository root, with Docker, Java 21+, and Maven 3.9+:

```sh
docker build -t copilot-runtime:1.0.105 testcontainers/image
mvn -f testcontainers/pom.xml install
mvn -f testcontainers/pom.xml -Pcontainer-tests verify
```

After the publishing workflow has run, use
`ghcr.io/brunoborges/copilot-sdk-integrations/copilot-runtime:1.0.105` instead of
building locally (provided that CLI release has been published). Other available
tags are `latest` and `<cli-version>-<YYYYMMDD>`. For reproducible CI, pin a
published digest; the version and daily tags are rebuilt to include base-image
updates. Every published multi-platform tag supports Linux amd64 and arm64.

The image includes the full `copilot` command and defaults to the SDK runtime
server. Inspect the bundled CLI without starting a server:

```sh
docker run --rm --entrypoint copilot copilot-runtime:1.0.105 --version
```

The CLI is installed at image build time, not downloaded at container startup.
Its upstream license and notices are retained under `/opt/copilot/`; the
integration's MIT license does not relicense the CLI.

The container tests use the real Java SDK and real runtime: authenticated TCP
connection, ping/status, wrong-secret rejection, a custom port, non-root execution,
and an end-to-end prompt using a local deterministic OpenAI-compatible provider.
No GitHub credentials or paid model calls are needed. Image downloads require
internet access at build time. Plain `mvn verify` runs only Docker-free unit tests;
the explicit `container-tests` profile fails rather than silently skipping when
Docker or the image is missing.

The daily publisher discovers the newest stable release of `github/copilot-cli`,
builds with that exact version, and tests both architectures before updating the
multi-platform tags. A failed release or compatibility test leaves `latest`
unchanged. Publication uses the Actions `GITHUB_TOKEN` with job-scoped
`packages: write`; no personal access token or registry secret is required.
See the guide for first-publication and package-visibility setup.

```xml
<dependency>
    <groupId>com.microsoft</groupId>
    <artifactId>copilot-testcontainers</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Add your preferred Copilot Java SDK separately (tested with `1.0.24`).

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
        var status = client.getStatus().join();
        System.out.println(status.getVersion());
        // Inject client into the application being tested.
    }
}
```

Starting a client does not require GitHub authentication. Sending prompts needs
either explicit runtime GitHub authentication with `withGitHubToken(...)` or a
session-level BYOK provider. The connection token is a separate, per-container
secret, not a GitHub access token.

See the [full guide](src/site/markdown/index.md) for the image contract,
Node.js/Python/.NET/Go recipes, framework wiring, networking, security, and CI.
Published documentation: [Testcontainers](https://brunoborges.github.io/copilot-sdk-integrations/testcontainers/).

## Layout

- `image/`: shared Linux amd64/arm64 runtime image and entrypoint.
- `src/main/java/`: optional Java Testcontainers helper.
- `src/test/java/`: Docker-free unit tests and real-runtime integration tests.
- `src/site/`: Maven documentation site (`mvn -f testcontainers/pom.xml site`).
