---
title: Getting started
---

<a id="getting-started"></a>

# Getting started

<a id="modules"></a>

## Modules

| Module | Artifact | Purpose |
| --- | --- | --- |
| `../core` | `copilot-core` | Shared with the other Java integrations: the `@CopilotAgent` API and the framework-neutral agent engine. |
| `runtime` | `copilot-micronaut` | Configuration, bean binding, and optional integrations. |
| `processor` | `copilot-micronaut-processor` | Compile-time visitor that registers each `@CopilotAgent` interface as Micronaut beans. |
| `test` | `copilot-micronaut-test` | Scripted `CopilotTestRuntime` and a Testcontainers `CopilotContainer`. |
| `example` | (not published) | A Micronaut HTTP application with conversational, bound, and streaming agents. |

<a id="install"></a>

## Install

Releases are published to GitHub Packages, which requires authentication even
for public packages. Add the repository to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>copilot-sdk-integrations</id>
        <url>https://maven.pkg.github.com/brunoborges/copilot-sdk-integrations</url>
    </repository>
</repositories>
```

Add matching credentials to `~/.m2/settings.xml`, using a
[personal access token (classic)](https://github.com/settings/tokens/new?scopes=read:packages)
with the `read:packages` scope:

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

See [Installing released packages](https://github.com/brunoborges/copilot-sdk-integrations#installing-released-packages)
for Gradle and GitHub Actions setup.

Add the runtime to a Micronaut 5 application:

```xml
<dependency>
    <groupId>com.microsoft</groupId>
    <artifactId>copilot-micronaut</artifactId>
    <version>0.1.0</version>
</dependency>
```

Add the processor next to Micronaut's own annotation processors:

```xml
<annotationProcessorPaths>
    <path>
        <groupId>io.micronaut</groupId>
        <artifactId>micronaut-inject-java</artifactId>
        <version>${micronaut.core.version}</version>
    </path>
    <path>
        <groupId>com.microsoft</groupId>
        <artifactId>copilot-micronaut-processor</artifactId>
        <version>0.1.0</version>
    </path>
    <!-- Generates tool metadata for @CopilotTool methods -->
    <path>
        <groupId>com.microsoft</groupId>
        <artifactId>copilot-sdk</artifactId>
        <version>2.0.1</version>
    </path>
</annotationProcessorPaths>
```

The SDK jar also contains an experimental-API checker that rejects the tool
processor's generated code, so list the processors explicitly, as in the example's
[`pom.xml`](https://github.com/brunoborges/copilot-sdk-integrations/blob/main/java/micronaut/example/pom.xml),
or pass `-Acopilot.experimental.allowed=true`.

To try unreleased changes, install the reactor locally from `java/micronaut` and
use `0.1.0-SNAPSHOT` for both artifacts instead:

```sh
./mvnw --batch-mode --no-transfer-progress install
```

<a id="connect-to-the-runtime"></a>

## Connect to the runtime

Point the integration at an installed, authenticated Copilot CLI, or set
`micronaut.copilot.cli-url` to use an existing TCP server:

```properties
micronaut.copilot.cli-path=/absolute/path/to/copilot
```

To run the runtime inside the JVM instead, set the experimental
`micronaut.copilot.connection=inprocess` (or `stdio` to pin the child-process
transport). It needs the platform runtime artifact (JNA already comes with the SDK):

```xml
<dependency>
  <groupId>com.microsoft</groupId>
  <artifactId>copilot-sdk-runtime</artifactId>
  <version>${copilot-sdk.version}</version>
  <classifier>darwin-arm64</classifier>
</dependency>
```

Classifiers are `linux-x64`, `linux-arm64`, `linuxmusl-x64`, `linuxmusl-arm64`,
`win32-x64`, `win32-arm64`, `darwin-x64`, and `darwin-arm64`. A `runtime.node`
next to the CLI named by `COPILOT_CLI_PATH`, or next to `copilot` on `PATH`, also
works. Startup fails with the missing dependency and the classifier for the
current platform when JNA or the runtime cannot be found.

In-process mode cannot be combined with `cli-path`, `cli-args`,
`working-directory`, `cli-url`, or `tcp-connection-token`; startup names every
conflicting setting. A JVM loads only one native runtime library. On JDK 24 and
later, add `--enable-native-access=ALL-UNNAMED` to avoid native access warnings.
`micronaut.copilot.connection=stdio` pins the child-process transport even when
`COPILOT_SDK_DEFAULT_CONNECTION=inprocess` is set.

Authentication follows the SDK/CLI's ambient rules. Never commit credentials.
**Model requests consume your account's Copilot usage.**

<a id="first-agent"></a>

## Your first agent

Declare an agent interface:

```java
@CopilotAgent(name = "micronaut-advisor",
        instructions = "Explain Micronaut concepts concisely using Java examples.")
public interface MicronautAdvisor {
    CompletableFuture<String> answer(@ConversationId String conversationId, @UserMessage String question);
}
```

Inject it and call it; no implementation class is required:

```java
@Controller("/advisor")
public class AdvisorController {

    private final MicronautAdvisor advisor;

    public AdvisorController(MicronautAdvisor advisor) {
        this.advisor = advisor;
    }

    @Post(value = "/{conversation}", consumes = MediaType.TEXT_PLAIN, produces = MediaType.TEXT_PLAIN)
    public CompletionStage<String> ask(String conversation, @Body String question) {
        return advisor.answer(conversation, question);
    }
}
```

Next, read [Agent interfaces](agents.html) for the full method contract.
