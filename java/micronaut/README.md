# copilot-micronaut

Micronaut integration for the [Copilot SDK for Java](https://github.com/github/copilot-sdk/tree/main/java),
targeting **Micronaut 5.2** (platform 5.2.3), **Java 21**, and Copilot SDK **1.0.24**.

It offers the same programming model as the [Spring Boot starter](../spring-boot/) and the
[Quarkus extension](../quarkus/): the `io.github.brunoborges.copilot.agent` API (`@CopilotAgent`,
`@ConversationId`, `@UserMessage`, `CopilotAgents`, hooks, listeners, customizers, token resolvers) is
identical, so agent interfaces move between frameworks unchanged. Agents are registered at compile time by an
annotation processor, in keeping with Micronaut's reflection-free dependency injection.

## Features

- A `CopilotClient` bean for a managed local runtime or an existing TCP server, with lazy (default) or eager
  startup and clean shutdown.
- Typed `@CopilotAgent` interfaces registered at compile time and injectable like any bean. They return
  `CompletableFuture<String>`, structured results (`CompletableFuture<T>`), synchronous `String`/`T`, or
  `Flux<CopilotAgentEvent>`, routed by `@ConversationId` or bound to application-owned instances via
  `CopilotAgents`.
- `@Requires` on an agent interface makes the agent conditional, like any other bean.
- Prompt templates, system instructions inline, from class-path resources, or from `SystemMessageConfig` beans.
- Persistent conversations with create/resume, per-conversation serialization, and bounded, non-blocking
  admission across all agents.
- Micronaut beans as tools through the SDK's `@CopilotTool` annotations.
- Declarative stdio/HTTP MCP servers and BYOK model providers per agent.
- Per-agent tool governance: allow/deny lists, declarative permission policies, and session hooks as beans
  (`@GlobalCopilotAgentHooks`, `@CopilotAgentHooksFor`).
- Per-user authentication through a `CopilotTokenResolver` bean or the current Micronaut Security
  `Authentication`. Micronaut's `PropagatedContext` (for example the current HTTP request) is propagated into
  tools, permission handlers, hooks, and listeners.
- Real cancellation: cancelling a future or `Flux`, or hitting the response timeout, aborts the in-flight
  runtime turn.
- Optional integrations, activated when the matching module is on the class path:
  - `micronaut-management`: a `copilot` readiness indicator that never force-starts a lazy client, a `copilot`
    section in `/info`, and a read-only `copilot` management endpoint that reports agents, execution capacity,
    models, and sessions.
  - `micronaut-micrometer-core`: `copilot.agent.invocation` and `copilot.agent.tool` timers, plus token and
    rejection counters.
  - Reactor (`micronaut-reactor`): `Flux` streaming.
  - `micronaut-security`: the identity token resolver.
- Test support: a scripted, in-memory Copilot runtime that replaces the client in `@MicronautTest`s, plus a
  Testcontainers `CopilotContainer`.

Permissions are denied unless you supply an explicit `PermissionHandler` bean or permission policy; the
integration never approves tool use implicitly. No artifacts have been published yet.

## Modules

| Module | Artifact | Purpose |
| --- | --- | --- |
| `../core` | `copilot-agent-core` | Shared with the other Java integrations: the `@CopilotAgent` API and the framework-neutral agent engine. |
| `runtime` | `copilot-micronaut` | Configuration, bean binding, and optional integrations. |
| `processor` | `copilot-micronaut-processor` | Compile-time visitor that registers each `@CopilotAgent` interface as Micronaut beans. |
| `test` | `copilot-micronaut-test` | Scripted `CopilotTestRuntime` and a Testcontainers `CopilotContainer`. |
| `example` | (not published) | A Micronaut HTTP application with conversational, bound, and streaming agents. |

## Quick start

Until releases are published, install the reactor locally:

```sh
./mvnw --batch-mode --no-transfer-progress install
```

Add the runtime to a Micronaut 5 application, and the processor next to Micronaut's own annotation processors:

```xml
<dependency>
    <groupId>io.github.brunoborges</groupId>
    <artifactId>copilot-micronaut</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```xml
<annotationProcessorPaths>
    <path>
        <groupId>io.micronaut</groupId>
        <artifactId>micronaut-inject-java</artifactId>
        <version>${micronaut.core.version}</version>
    </path>
    <path>
        <groupId>io.github.brunoborges</groupId>
        <artifactId>copilot-micronaut-processor</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </path>
    <!-- Generates tool metadata for @CopilotTool methods -->
    <path>
        <groupId>com.github</groupId>
        <artifactId>copilot-sdk-java</artifactId>
        <version>1.0.24</version>
    </path>
</annotationProcessorPaths>
```

The SDK jar also contains an experimental-API checker that rejects the tool processor's generated code, so list
the processors explicitly, as in the example's [`pom.xml`](example/pom.xml), or pass
`-Acopilot.experimental.allowed=true`.

Point the integration at an installed, authenticated Copilot CLI (or set `micronaut.copilot.cli-url` to use an
existing TCP server):

```properties
micronaut.copilot.cli-path=/absolute/path/to/copilot
```

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

Interfaces without a `@ConversationId` parameter are not beans. Create bound instances with
`CopilotAgents.create(Type.class)` or `CopilotAgents.resume(Type.class, id)`.

Authentication follows the SDK/CLI's ambient rules. Never commit credentials. **Model requests consume your
account's Copilot usage.**

## Referencing beans

Attributes ending in `Ref`/`Refs` (`toolRefs`, `systemMessageRef`, `permissionHandlerRef`, `listenerRefs`,
`customizerRefs`, `hookRefs`, `tokenResolverRef`) name beans. Give each referenced bean an explicit
`@Named("...")`:

```java
@Singleton
@Named("referenceTools")
public class ReferenceTools {

    @CopilotTool(value = "Look up a definition", name = "describe_term")
    public String describeTerm(@CopilotToolParam("Term") String term) { ... }
}
```

Beans looked up by type (hooks, return-type adapters, telemetry decorators) are ordered with Micronaut's
`@Order`/`Ordered`, lower values first.

The integration's default beans (`CopilotClient`, `CopilotClientOptions`, `CopilotAgents`, telemetry) are
`@Secondary`, so an application bean of the same type wins; use `@Replaces` to remove a default entirely.

## Configuration

All settings live under `micronaut.copilot`. The keys and defaults match the Spring Boot starter's
`copilot.*` properties:

```properties
micronaut.copilot.eager-start=false
micronaut.copilot.startup-timeout=30s
micronaut.copilot.execution.max-concurrent-invocations=8
micronaut.copilot.execution.max-outstanding-invocations=64

# Per-agent overrides of @CopilotAgent attributes
micronaut.copilot.agents.micronaut-advisor.model=gpt-5.2-codex
micronaut.copilot.agents.micronaut-advisor.response-timeout=2m
micronaut.copilot.agents.micronaut-advisor.excluded-tools=shell
micronaut.copilot.agents.micronaut-advisor.permissions.allow=read
micronaut.copilot.agents.micronaut-advisor.mcp-servers=github
micronaut.copilot.agents.micronaut-advisor.provider=foundry

# MCP servers
micronaut.copilot.mcp.servers.github.type=http
micronaut.copilot.mcp.servers.github.url=https://api.githubcopilot.com/mcp/

# BYOK providers
micronaut.copilot.providers.foundry.base-url=https://my-resource.openai.azure.com/openai/v1/
micronaut.copilot.providers.foundry.api-key=${FOUNDRY_API_KEY}
micronaut.copilot.providers.foundry.wire-api=responses
```

Switches:

| Property | Default | Effect |
| --- | --- | --- |
| `micronaut.copilot.enabled` | `true` | Registers the client, agents, and integrations. |
| `micronaut.copilot.health.enabled` | `true` | Adds the `copilot` readiness indicator when `micronaut-management` is present. |
| `micronaut.copilot.metrics.enabled` | `true` | Times agents and tools when a Micrometer `MeterRegistry` is present. |
| `micronaut.copilot.info.enabled` | `true` | Adds a `copilot` section to `/info` when `micronaut-management` is present. |
| `endpoints.copilot.enabled` | `false` | Serves `/copilot` and `/copilot/{agents,execution,models,sessions}`. The endpoint is sensitive by default; set `endpoints.copilot.sensitive=false` or secure it. |
| `micronaut.copilot.security.identity-token-resolver.enabled` | `false` | Authenticates agents as the current Micronaut Security user, using the token in its `micronaut.copilot.security.identity.token-attribute` attribute (default `accessToken`). |
| `micronaut.copilot.test-runtime.enabled` | `true` | Lets `copilot-micronaut-test` replace the client in tests. |

The readiness indicator and endpoint are tuned with `micronaut.copilot.health.{timeout,start-client,require-authentication}`
and `micronaut.copilot.endpoint.{timeout,start-client,show-instructions,include-all-sessions,show-session-summaries}`.
Reactor streams buffer up to `micronaut.copilot.streaming.buffer-capacity` (default `256`) events.

Agents and their bean references are validated at startup, so a missing tool bean or an unknown MCP server fails
the application instead of the first request.

## Testing

Add the test module:

```xml
<dependency>
    <groupId>io.github.brunoborges</groupId>
    <artifactId>copilot-micronaut-test</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

While it is on the class path, the `CopilotClient` is replaced by a scripted, in-memory runtime that never starts
the CLI. It is reset after each `@MicronautTest` method:

```java
@MicronautTest
class MicronautAdvisorTest {

    @Inject CopilotTestRuntime copilot;
    @Inject MicronautAdvisor advisor;

    @Test
    void answersWithTheReferenceTool() {
        copilot.agent("micronaut-advisor")
                .invokesTool("describe_term", Map.of("term", "micronaut"))
                .repliesUsing(turn -> "In short: " + turn.toolResult("describe_term"));

        assertTrue(advisor.answer("c-1", "What is Micronaut?").join().startsWith("In short: "));
        assertEquals(List.of("What is Micronaut?"), copilot.prompts("micronaut-advisor"));
    }
}
```

To test against a real Copilot CLI, set `micronaut.copilot.test-runtime.enabled=false`, start a
`CopilotContainer` with an image that runs `copilot --server`, and return its `properties()` from a
`TestPropertyProvider`.

## Building

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

GraalVM native images are not yet tested.

## License

[MIT](../../LICENSE)
