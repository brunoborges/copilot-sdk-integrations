# copilot-spring-boot

Spring Boot starter for the [Copilot SDK for Java](https://github.com/github/copilot-sdk/tree/main/java),
targeting **Spring Boot 4.1.1**, **Java 21**, and Copilot SDK **1.0.24**.

📖 **[Read the documentation](https://brunoborges.github.io/copilot-spring-boot/)**
([chapter sources](DOCS.md)).

## Features

- Auto-configured, Spring-managed `CopilotClient` for a managed local runtime or an
  existing TCP server, with lazy (default) or eager startup and clean shutdown.
- Typed `@CopilotAgent` interfaces returning `CompletableFuture<String>`, structured
  results (`CompletableFuture<T>` for records, enums, collections, and scalars via
  provider-native JSON Schema), or synchronous `String`/`T`, routed by
  `@ConversationId` or bound to application-owned instances via `CopilotAgents`.
- Prompt templates: `@UserMessage("Summarize {{doc}}")` inline or from resources,
  with `@P`-bound parameters validated at startup.
- Persistent conversations with create/resume, per-conversation serialization, and
  bounded, non-blocking admission across all agents.
- Append, replace, or customize system instructions inline, from resources, or from
  SDK `SystemMessageConfig` beans.
- Spring services as tools through the SDK's `@CopilotTool` annotations, invoked
  through the Spring proxy so transactions and security advice still apply.
- Declarative local (stdio) and remote (HTTP) MCP servers selected per agent.
- BYOK (bring your own key) model providers per agent: OpenAI, Azure OpenAI /
  Microsoft Foundry, Anthropic, Ollama, and other OpenAI-compatible endpoints.
- Per-user GitHub authentication through a `CopilotTokenResolver` or the Spring
  Security OAuth2 client, with the caller's security context, MDC, and observation
  propagated into tools, permission handlers, hooks, and listeners.
- Per-agent tool governance: allow/deny lists for built-in, custom, and MCP tools,
  declarative permission policies, and session hooks as Spring beans.
- Real cancellation: cancelling a future or `Flux`, or hitting the response
  timeout, aborts the in-flight runtime turn.
- Session-scoped listeners and SDK configuration customizers.
- Optional Actuator support: a `copilot` health indicator (never force-starts a lazy
  client), a read-only `copilot` endpoint for agents, models, sessions, and execution
  capacity, and an info section with starter/SDK versions.
- Optional Micrometer observations and token counters, and an optional Reactor
  module for `Flux<CopilotAgentEvent>` streaming.
- Test support module with a scripted, in-memory Copilot runtime (replies, streaming,
  tool calls into `@CopilotTool` beans, failures, recorded prompts) and a
  Testcontainers `CopilotContainer` wired through `@ServiceConnection`.

Permissions are denied unless you supply an explicit `PermissionHandler` bean; the
starter never approves tool use implicitly. No artifacts have been published yet.

## Modules

| Module | Purpose |
| --- | --- |
| `copilot-spring-boot-starter` | Dependency-only starter: SDK, auto-configuration, and Spring Boot starter. |
| `copilot-spring-boot-autoconfigure` | Client configuration, agent API, discovery, execution, tool binding, optional observations, and Actuator support. |
| `copilot-spring-boot-reactor` | Optional Reactor return-type adapter for streaming agent methods. |
| `copilot-spring-boot-test` | Test support: scripted in-memory `CopilotClient`, `@AutoConfigureCopilotTestRuntime`, and Testcontainers `CopilotContainer` with `@ServiceConnection`. |
| `copilot-spring-boot-example` | Executable, non-web example of raw SDK sessions, typed agents, bound instances, and streaming. |
| `copilot-spring-boot-site-skin` | Internal Maven Site layout for the documentation; not an application dependency. |

## Quick start

Until releases are published, install the reactor locally:

```sh
./mvnw --batch-mode --no-transfer-progress install
```

Add the starter to a Java 21 / Spring Boot 4.1.1 application:

```xml
<dependency>
    <groupId>io.github.brunoborges</groupId>
    <artifactId>copilot-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Point it at an installed, authenticated Copilot CLI. Alternatives (the
`COPILOT_CLI_PATH` variable, SDK runtime artifacts, or an existing TCP server) are
covered in [Getting started](src/site/markdown/getting-started.md) and the
[configuration reference](src/site/markdown/configuration.md):

```yaml
copilot:
  cli-path: /absolute/path/to/copilot
```

Declare an agent interface in your application's base package (or a subpackage):

```java
import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;
import java.util.concurrent.CompletableFuture;

@CopilotAgent(name = "spring-advisor",
        instructions = "Explain Spring Boot concepts concisely using Java examples.")
public interface SpringAdvisor {
    CompletableFuture<String> answer(
            @ConversationId String conversationId,
            @UserMessage String question);
}
```

Inject it and call it; no implementation class is required. Calls sharing a
conversation key share persisted history:

```java
var first = advisor.answer("tenant-1/customer-42", "What is dependency injection?");
var followUp = first.thenCompose(answer ->
        advisor.answer("tenant-1/customer-42", "Summarize your previous answer."));
```

For methods without a conversation-key argument, create
[bound instances](src/site/markdown/instances.md) through `CopilotAgents`.

Authentication follows the SDK/CLI's ambient rules: authenticate the local CLI
or supply credentials through an SDK-supported environment variable such as
`COPILOT_GITHUB_TOKEN`. Never commit credentials. **Model requests consume your
account's Copilot usage.**

Agents can also use
[BYOK (bring your own key)](src/site/markdown/byok.md) model providers with your
own API keys, without a GitHub Copilot subscription:

```yaml
copilot:
  providers:
    foundry:
      base-url: https://my-resource.openai.azure.com/openai/v1/
      api-key: ${FOUNDRY_API_KEY}
      wire-api: responses
  agents:
    spring-advisor:
      model: gpt-5.2-codex
      provider: foundry
```

See also the SDK's
[BYOK documentation](https://github.com/github/copilot-sdk/blob/main/docs/auth/byok.md).

## Documentation

| Topic | Chapter |
| --- | --- |
| Modules, installation, and a first agent | [Getting started](src/site/markdown/getting-started.md) |
| Native SDK access and bean overrides | [SDK client](src/site/markdown/client.md) |
| Annotations, method contracts, and discovery | [Agent interfaces](src/site/markdown/agents.md) |
| Application-owned conversations | [Bound instances](src/site/markdown/instances.md) |
| Persistence, serialization, errors, and cancellation | [Conversations and lifecycle](src/site/markdown/conversations.md) |
| Append, replace, and customize modes | [System instructions](src/site/markdown/instructions.md) |
| `@CopilotTool` services and proxies | [Spring tool beans](src/site/markdown/tools.md) |
| Local and remote MCP servers | [MCP servers](src/site/markdown/mcp.md) |
| Bring-your-own-key model providers | [BYOK providers](src/site/markdown/byok.md) |
| Per-user tokens, OAuth2, and context propagation | [Per-user authentication](src/site/markdown/authentication.md) |
| Listeners and customizers | [Events and customizers](src/site/markdown/events.md) |
| Tool filtering, permission policies, and hooks | [Tool governance, permissions, and hooks](src/site/markdown/governance.md) |
| Every `copilot.*` property | [Configuration reference](src/site/markdown/configuration.md) |
| `Flux<CopilotAgentEvent>` streaming | [Reactive streaming](src/site/markdown/streaming.md) |
| Micrometer observations and token counters | [Observability](src/site/markdown/observability.md) |
| Health indicator, info, and `copilot` endpoint | [Actuator](src/site/markdown/actuator.md) |
| Custom telemetry and return types | [Extension SPIs](src/site/markdown/extensions.md) |
| Scripted test runtime and Testcontainers | [Testing](src/site/markdown/testing.md) |
| Running the example modes | [Example application](src/site/markdown/examples.md) |
| Authorization boundaries and troubleshooting | [Security and troubleshooting](src/site/markdown/operations.md) |

See [DOCS.md](DOCS.md) to build and preview the documentation site locally.

## Example application

```sh
./mvnw --batch-mode --no-transfer-progress -pl copilot-spring-boot-example -am package
java -jar copilot-spring-boot-example/target/copilot-spring-boot-example-0.1.0-SNAPSHOT.jar --help
java -jar copilot-spring-boot-example/target/copilot-spring-boot-example-0.1.0-SNAPSHOT.jar \
  --copilot.cli-path=/absolute/path/to/copilot --agent
```

`--help` makes no model requests. The default mode and `--agent`, `--stream`, and
`--instance` modes make real requests; see the
[example chapter](src/site/markdown/examples.md) for what each does.

## Building

Install JDK 21 and set `JAVA_HOME`. The committed Maven Wrapper downloads
Maven 3.9.15 on first use.

```sh
./mvnw --batch-mode --no-transfer-progress verify       # macOS / Linux
mvnw.cmd --batch-mode --no-transfer-progress verify     # Windows
```

Tests use mocks and local protocol stubs; they need neither credentials nor a
running Copilot runtime and make no paid requests. GitHub Actions runs the same
build on Java 21 for pushes and pull requests.

## License

[MIT](LICENSE).
