# copilot-quarkus

Quarkus extension for the [Copilot SDK for Java](https://github.com/github/copilot-sdk/tree/main/java),
targeting **Quarkus 3.40**, **Java 21**, and Copilot SDK **1.0.24**.

📖 **[Read the documentation](https://brunoborges.github.io/copilot-sdk-integrations/quarkus/)**
([chapter sources](src/site/markdown/)).

It offers the same programming model as the [Spring Boot starter](../spring-boot/):
the `io.github.brunoborges.copilot.agent` API (`@CopilotAgent`, `@ConversationId`,
`@UserMessage`, `CopilotAgents`, hooks, listeners, customizers, token resolvers) is
identical, so agent interfaces can move between the two frameworks unchanged. Wiring
is done at build time by ArC (Quarkus CDI) instead of Spring auto-configuration.

## Features

- CDI-managed `CopilotClient` for a managed local runtime or an existing TCP server,
  with lazy (default) or eager startup and clean shutdown.
- Typed `@CopilotAgent` interfaces discovered at build time and injectable with
  `@Inject`. They return `CompletableFuture<String>`, structured results
  (`CompletableFuture<T>`), synchronous `String`/`T`, or `Multi<CopilotAgentEvent>`,
  routed by `@ConversationId` or bound to application-owned instances via
  `CopilotAgents`.
- Prompt templates (`@UserMessage("Summarize {{doc}}")`, `@P` parameters) and system
  instructions inline, from class-path resources, or from `SystemMessageConfig` beans.
- Persistent conversations with create/resume, per-conversation serialization, and
  bounded, non-blocking admission across all agents.
- CDI beans as tools through the SDK's `@CopilotTool` annotations, invoked through the
  bean's client proxy so interceptors (transactions, security, ...) still apply.
- Declarative stdio/HTTP MCP servers and BYOK model providers per agent.
- Per-agent tool governance: allow/deny lists, declarative permission policies, and
  session hooks as CDI beans (`@GlobalCopilotAgentHooks`, `@CopilotAgentHooksFor`).
- Per-user authentication through a `CopilotTokenResolver` bean or the current Quarkus
  Security `SecurityIdentity`. The CDI request context is propagated into tools,
  permission handlers, hooks, and listeners.
- Real cancellation: cancelling a future or `Multi`, or hitting the response timeout,
  aborts the in-flight runtime turn.
- Optional integrations, activated when the matching extension is present:
  - SmallRye Health: a `copilot` readiness check that never force-starts a lazy client.
  - Micrometer: `copilot.agent.invocation` and `copilot.agent.tool` timers, plus token
    and rejection counters.
  - Mutiny streaming.
  - `quarkus-info`: a `copilot` section with extension and SDK versions.
  - A read-only diagnostics endpoint at `/q/copilot` that reports agents, execution
    capacity, models, and sessions.
- Test support: a scripted, in-memory Copilot runtime that replaces the client in
  `@QuarkusTest`s, plus a Testcontainers `CopilotContainer` test resource.

Permissions are denied unless you supply an explicit `PermissionHandler` bean or
permission policy; the extension never approves tool use implicitly. No artifacts have
been published yet.

## Modules

| Module | Artifact | Purpose |
| --- | --- | --- |
| `runtime` | `copilot-quarkus` | The extension: agent API, configuration, CDI beans, and optional integrations. |
| `deployment` | `copilot-quarkus-deployment` | Build steps: agent discovery and validation, synthetic agent beans, hook catalog, endpoint routes. |
| `test` | `copilot-quarkus-test` | Scripted `CopilotTestRuntime` and the `CopilotContainerTestResource` for Testcontainers. |
| `example` | (not published) | A Quarkus REST application with conversational, bound, and streaming agents. |
| `site-skin` | `copilot-quarkus-site-skin` | Internal Maven Site layout for the documentation; not an application dependency. |

## Quick start

Until releases are published, install the reactor locally:

```sh
./mvnw --batch-mode --no-transfer-progress install
```

Add the extension to a Quarkus 3.40 application:

```xml
<dependency>
    <groupId>io.github.brunoborges</groupId>
    <artifactId>copilot-quarkus</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Point it at an installed, authenticated Copilot CLI (or set `quarkus.copilot.cli-url`
to use an existing TCP server):

```properties
quarkus.copilot.cli-path=/absolute/path/to/copilot
```

Declare an agent interface:

```java
@CopilotAgent(name = "quarkus-advisor",
        instructions = "Explain Quarkus concepts concisely using Java examples.")
public interface QuarkusAdvisor {
    CompletableFuture<String> answer(@ConversationId String conversationId, @UserMessage String question);
}
```

Inject it and call it; no implementation class is required:

```java
@Path("/advisor")
public class AdvisorResource {

    @Inject
    QuarkusAdvisor advisor;

    @POST
    @Path("/{conversation}")
    public CompletionStage<String> ask(@PathParam("conversation") String conversation, String question) {
        return advisor.answer(conversation, question);
    }
}
```

Interfaces without a `@ConversationId` parameter are not beans. Create bound instances
with `CopilotAgents.create(Type.class)` or `CopilotAgents.resume(Type.class, id)`.

Authentication follows the SDK/CLI's ambient rules. Never commit credentials. **Model
requests consume your account's Copilot usage.**

## Referencing beans

Attributes ending in `Ref`/`Refs` (`toolRefs`, `systemMessageRef`,
`permissionHandlerRef`, `listenerRefs`, `customizerRefs`, `hookRefs`,
`tokenResolverRef`) name CDI beans. Unlike Spring, CDI beans have no implicit name.
Give each referenced bean an explicit `@Named("...")`. `@Named` beans are never removed
as unused.

```java
@ApplicationScoped
@Named("referenceTools")
public class ReferenceTools {

    @CopilotTool(value = "Look up a definition", name = "describe_term")
    public String describeTerm(@CopilotToolParam("Term") String term) { ... }
}
```

Enable the SDK's `CopilotToolProcessor` annotation processor (artifact
`com.github:copilot-sdk-java`) in your compiler configuration, as in the example's
[`pom.xml`](example/pom.xml).

Beans looked up by type (hooks, return-type adapters, telemetry decorators) are
ordered by `@Priority`, with **higher** values first, which is the ArC convention.
Spring's `@Order` runs lower values first.

## Configuration

All runtime settings live under `quarkus.copilot`. The keys and defaults match the
Spring Boot starter's `copilot.*` properties:

```properties
quarkus.copilot.eager-start=false
quarkus.copilot.startup-timeout=30s
quarkus.copilot.execution.max-concurrent-invocations=8
quarkus.copilot.execution.max-outstanding-invocations=64

# Per-agent overrides of @CopilotAgent attributes
quarkus.copilot.agents.quarkus-advisor.model=gpt-5.2-codex
quarkus.copilot.agents.quarkus-advisor.response-timeout=2m
quarkus.copilot.agents.quarkus-advisor.excluded-tools=shell
quarkus.copilot.agents.quarkus-advisor.permissions.allow=read
quarkus.copilot.agents.quarkus-advisor.mcp-servers=github
quarkus.copilot.agents.quarkus-advisor.provider=foundry

# MCP servers
quarkus.copilot.mcp.servers.github.type=http
quarkus.copilot.mcp.servers.github.url=https://api.githubcopilot.com/mcp/

# BYOK providers
quarkus.copilot.providers.foundry.base-url=https://my-resource.openai.azure.com/openai/v1/
quarkus.copilot.providers.foundry.api-key=${FOUNDRY_API_KEY}
quarkus.copilot.providers.foundry.wire-api=responses
```

Build-time switches:

| Property | Default | Effect |
| --- | --- | --- |
| `quarkus.copilot.enabled` | `true` | Registers the client, agents, and integrations. |
| `quarkus.copilot.health.enabled` | `true` | Adds the `copilot` readiness check when SmallRye Health is present. |
| `quarkus.copilot.metrics.enabled` | `true` | Times agents and tools when Micrometer is present. |
| `quarkus.copilot.info.enabled` | `true` | Adds a `copilot` section to `/q/info` when `quarkus-info` is present. |
| `quarkus.copilot.endpoint.enabled` | `false` | Serves `/q/copilot`, `/q/copilot/{agents,execution,models,sessions}` (requires `quarkus-vertx-http`). |
| `quarkus.copilot.security.identity-token-resolver.enabled` | `false` | Authenticates agents as the current `SecurityIdentity` using its bearer token. |
| `quarkus.copilot.test-runtime.enabled` | `true` | Lets `copilot-quarkus-test` replace the client in tests. |

Agents and their bean references are validated at startup, so a missing tool bean or
an unknown MCP server fails the application instead of the first request.

## Testing

Add the test module:

```xml
<dependency>
    <groupId>io.github.brunoborges</groupId>
    <artifactId>copilot-quarkus-test</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

While it is on the class path, the `CopilotClient` is replaced by a scripted,
in-memory runtime that never starts the CLI. It is reset after each `@QuarkusTest`
method:

```java
@QuarkusTest
class QuarkusAdvisorTest {

    @Inject CopilotTestRuntime copilot;
    @Inject QuarkusAdvisor advisor;

    @Test
    void answersWithTheReferenceTool() {
        copilot.agent("quarkus-advisor")
                .invokesTool("describe_term", Map.of("term", "quarkus"))
                .repliesUsing(turn -> "In short: " + turn.toolResult("describe_term"));

        assertTrue(advisor.answer("c-1", "What is Quarkus?").join().startsWith("In short: "));
        assertEquals(List.of("What is Quarkus?"), copilot.prompts("quarkus-advisor"));
    }
}
```

To test against a real Copilot CLI, set `quarkus.copilot.test-runtime.enabled=false` and
use `@WithTestResource(CopilotContainerTestResource.class)` with an image that runs
`copilot --server` (see the [testing chapter](https://brunoborges.github.io/copilot-sdk-integrations/quarkus/testing.html#testcontainers-and-a-real-cli-server)).

## Documentation site

Chapter sources live in `src/site/markdown/`. Render the site locally (no model
requests or authentication needed):

```sh
./mvnw --batch-mode --no-transfer-progress -pl site-skin -am install
./mvnw --batch-mode --no-transfer-progress -N site
```

Open `target/site/index.html`, or run `./mvnw -N site:run` and browse
`http://localhost:8080`.

## Building

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

Native images are not yet tested. Agent interfaces are registered as dynamic proxies
and tool metadata classes for reflection, but the Copilot SDK itself may require
additional native configuration.

## License

[MIT](../LICENSE)
