# Bridges for other Java frameworks

Status: **proposal**. This document evaluates which Java frameworks would benefit from a
Copilot integration built on the [Copilot SDK for Java](https://github.com/github/copilot-sdk)
and the shared engine in [`core/`](core/) (`copilot-agent-core`).

Available today: [Spring Boot](spring-boot/) and [Quarkus](quarkus/).

**Decision:** [Apache Camel](camel/DESIGN.md) is the next Java integration. Every other bridge in
this document is a nice-to-have, kept here for reference and not scheduled.

## How a bridge is built

Every Java bridge is thin, because the agent engine is already framework-neutral. A bridge
provides:

1. **Container adapters** for `BindingContext`: a `BeanLookup` over the framework's DI
   container, a `PropertyResolver` over its configuration, and a `ResourceLookup`.
2. **Client lifecycle**: start and stop one `CopilotClient` with the container (lazy or eager),
   reusing an existing client when one is already registered.
3. **Agent discovery**: find `@CopilotAgent` interfaces (classpath scan, build-time index, or
   annotation processor) and register proxies as injectable beans.
4. **Native idioms**: the framework's own way to express request scope and identity, health,
   metrics, tracing, and tests.

Anything that requires changes in `copilot-agent-core` (for example a shared conversation
repository) should land there first, so every bridge benefits.

## Plain Java bootstrap: nice to have

A **container-less bootstrap** could be added to `copilot-agent-core`: a
builder that accepts tools, hooks, and listeners as objects (backed by a map-based
`BeanLookup`) and a `Properties`/environment-based `PropertyResolver`.

```java
CopilotAgents agents = CopilotAgentsBuilder.create()
    .client(CopilotClient.builder().build())
    .bean("orderTools", new OrderTools(repository))
    .properties(System.getenv(), "COPILOT_")
    .agents(TriageAgent.class)
    .build();
```

This would unlock frameworks without a DI container (Javalin, Helidon SE, Vert.x, Dropwizard,
Jenkins plugins, CLIs built with JBang/picocli). Camel does not need it: its `Registry` already
backs a `BeanLookup`.

## Nice-to-have frameworks

### 1. Spring AI and LangChain4j adapters

**What:** implement the abstractions those libraries already standardize on, backed by the
Copilot runtime:

- Spring AI: `ChatModel` / `StreamingChatModel`, plus a `ToolCallback` bridge so Spring AI
  tools can be registered as Copilot tools.
- LangChain4j: `ChatModel` / `StreamingChatModel`, with LangChain4j `@Tool` methods exposed as
  Copilot tools.

**Why:** this is the Java equivalent of the [.NET `IChatClient`](../dotnet/) integration and the
[Node.js AI SDK adapter](../nodejs/). It lets existing Spring AI and LangChain4j applications
(including Quarkus LangChain4j and Camel's LangChain4j components) switch to Copilot models and
GitHub authentication with a configuration change.

**Caveats:** these abstractions model stateless chat completion, while the Copilot runtime is a
stateful agent loop with its own tools. The adapter must document the mapping (a session per
call or per memory ID, runtime built-in tools disabled, tool execution delegated back to the
host library) and should expose the full agent runtime only through the native
`@CopilotAgent` API.

### 2. Jakarta EE / MicroProfile (portable CDI extension)

**What:** a portable CDI extension (`copilot-cdi`) with MicroProfile Config, Health, Telemetry,
and Fault Tolerance integration.

**Why:** one artifact covers Open Liberty, WildFly, Payara, TomEE, and Helidon MP, a large
share of enterprise Java. The Quarkus extension already maps the engine onto CDI (`@Named`
beans, `@Priority` ordering), so most of the `BeanLookup` logic carries over; the difference is
runtime discovery (`ProcessAnnotatedType`, `AfterBeanDiscovery`) instead of Quarkus build steps.

**Idioms:** `@Inject TriageAgent`, `@ConfigProperty`-backed settings under `copilot.*`,
`@Readiness` health check, MP Telemetry spans, MP Fault Tolerance (`@Retry`, `@Timeout`) on
agent methods, `SecurityContext`/JWT principal feeding `CopilotTokenResolver`, and
`ManagedExecutorService` for async invocations.

### 3. Micronaut

**What:** a Micronaut module using compile-time bean definitions.

**Why:** Micronaut's compile-time DI and GraalVM focus parallel Quarkus, so the agent-discovery
and native-image work from the Quarkus extension translates directly to a Micronaut annotation
processor (`TypeElementVisitor`) that generates bean definitions for `@CopilotAgent`
interfaces. `BeanLookup` maps onto `BeanContext` with `@Named` qualifiers and `@Order`.

**Idioms:** `@ConfigurationProperties("copilot")`, Micronaut Management health indicator,
Micrometer metrics, Micronaut Tracing, `@MicronautTest` with the scripted runtime, and
Micronaut Security's authenticated principal for per-user tokens.

### 4. Durable workflow engines (Temporal, Spring Batch)

**What:**

- **Temporal Java SDK:** agent invocations as activities, with the conversation ID kept in
  workflow state; permission requests become signals, so a human approval can arrive hours
  later without holding a thread or losing progress.
- **Spring Batch / Jakarta Batch:** an agent-backed `ItemProcessor` for bulk classification or
  extraction, with chunk-level retry, skip policies, and token-usage metrics per step.

**Why:** agent work is long-running, non-deterministic, and costly. Durable execution gives
retries, timeouts, and exactly-once orchestration around it, and fits human-in-the-loop
approvals naturally.

**Caveat:** agent calls must stay inside activities or processors, never in deterministic
workflow code.

### 5. Reactive and messaging

- **Vert.x:** a `Future`-based client and an event-bus service (`copilot.agent.<name>`
  addresses) so verticles call agents without blocking the event loop; streaming deltas map to
  `ReadStream`. Also benefits Quarkus reactive users.
- **MicroProfile Reactive Messaging / SmallRye:** an agent processor between `@Incoming` and
  `@Outgoing` channels (Kafka, AMQP), with acknowledgement only after the agent turn completes.
- **Spring Cloud Stream / Spring Integration:** a `Function<Message<?>, Message<?>>` bean and an
  outbound gateway. Spring Integration overlaps with Camel's EIP model and can reuse much of the
  Camel design (tool channels, permission channels).

### 6. Stream processing (Apache Flink, Kafka Streams)

**What:** a Flink `AsyncFunction` (async I/O operator) and a Kafka Streams processor that
enrich records with agent output.

**Why and why not:** useful for low-volume, high-value streams (incident triage, fraud case
summaries). Latency, cost, and quota make it a poor fit for high-volume streams, so the bridge
must ship with rate limiting, sampling, and capacity bounds, and be documented as an
enrichment step, not a per-event default.

### 7. Developer tooling hosts

- **Jenkins:** a pipeline step (`copilotAgent(prompt: ..., tools: [...])`) for build triage
  and release notes, using Jenkins credentials for the GitHub token. Builds on the tier-0
  bootstrap.
- **JUnit 5:** promote the scripted runtime from the per-framework test modules into a
  standalone `copilot-agent-test` artifact with a JUnit extension, shared by every bridge.

## Prioritization

| Bridge | Reach | Effort (on top of core) | Distinct value | Priority |
| --- | --- | --- | --- | --- |
| Apache Camel | High (integration) | Medium | Routes as tools, EIPs around agents | **Next** ([design](camel/DESIGN.md)) |
| Spring AI / LangChain4j adapters | Very high | Medium | Drop-in for existing AI apps | Nice to have |
| Jakarta EE / MicroProfile CDI | High (enterprise) | Medium (reuse Quarkus CDI mapping) | Covers many app servers at once | Nice to have |
| Micronaut | Medium | Medium (reuse Quarkus build-time ideas) | Native image, compile-time DI | Nice to have |
| Plain Java bootstrap | All non-DI apps | Low | Enables container-less bridges | Nice to have |
| Temporal / Spring Batch | Medium | Low-medium | Durable, human-in-the-loop agent work | Nice to have |
| Vert.x / Reactive Messaging / Spring Integration | Medium | Low-medium | Non-blocking and messaging pipelines | Nice to have |
| Flink / Kafka Streams | Niche | Medium | Agent enrichment in streams | Nice to have |
| Jenkins / JUnit | Niche to broad | Low | Tooling and shared tests | Nice to have |

Frameworks considered and not recommended as separate bridges: Helidon MP (covered by the CDI
extension), Dropwizard and Javalin (covered by the plain Java bootstrap), and Jakarta Batch
(covered by the CDI extension plus a processor example).

## Cross-cutting work in `copilot-agent-core`

Needed for Camel, and reusable by any later bridge:

1. A tool-definition SPI that accepts externally described tools (Camel routes first; later
   Spring AI `ToolCallback`s or LangChain4j tools) in addition to SDK `@CopilotTool` methods.
2. Async-first invocation (`CompletionStage`) so the Camel producer can be an `AsyncProcessor`.
3. A `CopilotConversationRepository` SPI for clustered conversation mappings.
4. A scripted-runtime test artifact usable outside the Spring Boot and Quarkus test modules.

Only for nice-to-have bridges: the container-less bootstrap.
