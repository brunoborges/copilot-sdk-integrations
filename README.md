# copilot-spring-boot

Spring Boot starter for the [Copilot SDK for Java](https://github.com/github/copilot-sdk/tree/main/java), targeting
**Spring Boot 4.1.1** and **Java 21**.

See [DOCS.md](DOCS.md) for the API and configuration reference, including a
linked table of contents, defaults, validation rules, and extension contracts.

## Status

The starter auto-configures the SDK's `CopilotClient` and `CopilotClientOptions`,
supports managed local runtimes and existing TCP servers, and closes its client
when the Spring context shuts down. Connections are lazy by default, with opt-in
eager startup. It uses SDK **1.0.24**.

Typed `@CopilotAgent` interfaces provide asynchronous text responses backed by
conversation-specific SDK sessions, including routed or instance-bound identity, create/resume, scoped listeners,
and all three system-message modes. Spring tool services, optional Micrometer
observations, bounded execution, and an optional Reactor event-stream module
extend that integration. No artifacts have been published. Automatic
permission approval and experimental in-process hosting are not provided.

## Project structure

| Module | Purpose |
| --- | --- |
| `copilot-spring-boot-parent` | Root Maven parent and reactor with shared dependency and plugin management. |
| `copilot-spring-boot-autoconfigure` | Client/options beans, typed agent proxies, properties, metadata, and lifecycle management. The SDK is an optional dependency. |
| `copilot-spring-boot-starter` | Dependency-only JAR combining the SDK, auto-configuration module, and Spring Boot starter. |
| `copilot-spring-boot-reactor` | Optional Reactor integration providing bounded `Flux<CopilotAgentEvent>` agent methods; not included in the basic starter. |
| `copilot-spring-boot-example` | Executable, non-web application demonstrating raw SDK sessions and typed agents with independent conversations. |

The autoconfigure, starter, and Reactor modules are ordinary library JARs; only the
example is an executable Spring Boot application.
Maven coordinates use the group `io.github.brunoborges` and version
`0.1.0-SNAPSHOT`.

## Using the starter

Until releases are published, build and install this reactor locally:

```sh
./mvnw --batch-mode --no-transfer-progress install
```

Then add this dependency to a Java 21 / Spring Boot 4.1.1 application:

```xml
<dependency>
    <groupId>io.github.brunoborges</groupId>
    <artifactId>copilot-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Managed local runtime

Use an installed Copilot CLI executable:

```yaml
copilot:
  cli-path: /absolute/path/to/copilot
  working-directory: /path/to/project
```

Alternatively, set the SDK's `COPILOT_CLI_PATH` environment variable. When no
explicit executable is provided, SDK 1.0.24 resolves a runtime wrapper from a
platform classifier JAR. Add the matching runtime artifact to your application,
for example for Linux x64:

```xml
<dependency>
    <groupId>com.github</groupId>
    <artifactId>copilot-sdk-java-runtime</artifactId>
    <version>1.0.24</version>
    <classifier>linux-x64</classifier>
</dependency>
```

Use the classifier for your deployment platform, such as `linux-arm64`,
`darwin-arm64`, or `win32-x64`. The starter deliberately does not bundle
platform-specific binaries or install the CLI. An explicit CLI path or an
appropriate runtime artifact is required for local use.

Authentication follows the SDK/CLI's ambient authentication rules. Authenticate
the local CLI before use or supply credentials through the SDK-supported
environment mechanism (for example `COPILOT_GITHUB_TOKEN`). Access to Copilot
requires the appropriate account entitlement. Do not put credentials in source
control. The starter does not grant tool permissions or change authentication
policy.

### Existing external server

```yaml
copilot:
  cli-url: localhost:4321
  tcp-connection-token: ${COPILOT_SERVER_TOKEN}
```

The token is optional if the server does not require one. Supported address
forms are a port alone (`4321`), `host:port`, or an HTTP-style URI with an
explicit port (`http://localhost:4321`). IPv6 hosts must be bracketed
(`[::1]:4321`). Ports must be in the range 1-65535. Credentials in the URI,
paths, queries, and fragments are rejected.

**SDK 1.0.24 uses raw TCP, not HTTP or TLS, for this connection.** Even an
`https://` address does not enable encryption. Use a trusted local connection
or a separately secured tunnel; do not send tokens over untrusted networks.
The external server manages GitHub authentication and its own runtime process.
Closing the starter's client disconnects it without shutting down that server.

External-server mode cannot be combined with `cli-path`, `cli-args`, or
`working-directory`. `tcp-connection-token` requires `cli-url`.

### Injecting the SDK client

This complete application injects the native SDK client and sends a protocol
ping, without making a model request:

```java
package example;

import com.github.copilot.CopilotClient;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    ApplicationRunner pingCopilot(CopilotClient client) {
        return args -> System.out.println(
                client.ping("hello").get(30, TimeUnit.SECONDS));
    }
}
```

The first SDK request connects automatically, so this example connects from its
application runner. Applications that do not make a request can start without
an available runtime. For model interactions, use the SDK's session API directly
and supply an explicit permission handler appropriate to your application.
Close sessions you create; do not close the shared injected client yourself.

## Configuration

| Property | Default | Purpose |
| --- | --- | --- |
| `copilot.enabled` | `true` | Enable client auto-configuration. |
| `copilot.eager-start` | `false` | Connect while creating the client bean instead of on first use. |
| `copilot.startup-timeout` | `30s` | Positive duration bounding the wait for an eager connection. |
| `copilot.cli-path` | SDK resolution | Executable for the managed local runtime. |
| `copilot.cli-args` | Empty list | Extra local runtime arguments. |
| `copilot.working-directory` | SDK default (application working directory) | Working directory for the local runtime. |
| `copilot.log-level` | SDK default (`info`) | Runtime logging level, passed through to the SDK. |
| `copilot.cli-url` | Unset | Address of an existing TCP runtime server. |
| `copilot.tcp-connection-token` | Unset | External server token; use an environment placeholder. |

Optional string properties must not be blank. Generated Spring configuration
metadata provides property descriptions and defaults for IDEs.

With `eager-start=true`, a failed, interrupted, or timed-out connection fails
application startup and closes the partially initialized client. The startup
timeout bounds the connection wait, not the subsequent SDK cleanup. Normal
shutdown uses the SDK's `close()` exactly once. SDK 1.0.24 bounds its own
cleanup phases and logs internal shutdown failures at Java logging level
`FINE`; its `close()` API does not propagate those failures.

### Custom beans

Provide a `CopilotClientOptions` bean for advanced SDK options:

```java
@Bean
CopilotClientOptions copilotClientOptions() {
    return new CopilotClientOptions()
            .setCliPath("/absolute/path/to/copilot")
            .setLogLevel("debug");
}
```

This replaces connection-property mapping entirely. `eager-start` and
`startup-timeout` still control the starter-owned client. SDK options such as
`autoStart` retain the values you supply.

Provide your own `CopilotClient` bean to replace client auto-configuration.
Client auto-configuration does not create an options bean, bind its properties, start,
reconfigure, or add cleanup callbacks to a user-provided client. Your bean
definition owns its lifecycle (including Spring's normal inferred `close`
destroy method unless you disable it). Typed agents can still use this client;
`copilot.enabled=false` disables both client and agent auto-configuration.

Using only the autoconfigure module without the SDK is safe: configuration
backs off when `CopilotClient` is not on the classpath. The starter brings the
SDK transitively.

## Typed agents

Declare a routed interface in your Spring Boot application's base package (or a
subpackage) and inject it as a Spring bean:

```java
import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;
import java.util.concurrent.CompletableFuture;

@CopilotAgent(name = "spring-advisor",
        instructions = "Explain Spring Boot concepts concisely using Java examples.",
        permissionHandler = "denyPermissions")
public interface SpringAdvisor {
    CompletableFuture<String> answer(
            @ConversationId String conversationId,
            @UserMessage String question);
}
```

`permissionHandler` refers to a Spring bean implementing the SDK's
`PermissionHandler`, such as the example module's `DenyPermissions`. When omitted,
the integration supplies a handler that logs and rejects permission requests;
it never selects an approving handler implicitly. This is not a runtime sandbox.

```java
var first = advisor.answer("tenant-1/customer-42", "What is dependency injection?");
var second = advisor.answer("tenant-1/customer-99", "What are virtual threads?");
var followUp = first.thenCompose(answer ->
        advisor.answer("tenant-1/customer-42", "Summarize your previous answer."));
CompletableFuture.allOf(followUp, second).join();
```

The basic starter supports abstract methods returning `CompletableFuture<String>`;
the optional Reactor module also supports `Flux<CopilotAgentEvent>`. Each method
can use two `String` parameters (`@ConversationId` and `@UserMessage`, in either
order) for routing, or one `@UserMessage String` for bound instances. Do not mix
forms in an interface. Unsupported contracts fail at bean initialization; blank/null
invocation arguments produce a failed future without contacting the runtime.
Messages are passed verbatim, without a template language or automatic retries.
Default interface methods execute locally.

### Conversation-bound instances

Use a business interface without an ID argument when application code should
own a distinct conversation instance:

```java
@CopilotAgent(name = "advisor", instructions = "Explain Spring Boot concisely.")
public interface Advisor {
    CompletableFuture<String> answer(@UserMessage String question);
}
```

Inject `CopilotAgents`, not the ID-less `Advisor` interface:

```java
String savedKey;
try (var instance = agents.create(Advisor.class)) {
    instance.agent().answer("What is dependency injection?").join();
    instance.agent().answer("Show an example of that.").join();
    savedKey = instance.conversationId();
}

try (var resumed = agents.resume(Advisor.class, savedKey)) {
    resumed.agent().answer("Continue our discussion.").join();
}
```

Each `create(...)` generates an independent key; the caller need not manage it
unless later resumption is needed. ID-less definitions do not become unsafe
singleton conversations. Handles bind identity while each invocation still
creates/resumes and detaches its SDK attachment.

Binding is lazy: `resume(...)` checks history when invoked and fails with
`CopilotConversationNotFoundException` rather than creating an empty history.
An established new handle likewise refuses to reset history if it disappears.
Closing rejects new calls and waits for actual admitted work/cleanup, even
after a result future is cancelled. It neither closes the shared client nor
deletes persisted history. Await complete workflows before leaving the resource
scope, and do not block in close from SDK callbacks or event-loop threads.
See [the instance API reference](DOCS.md#bound-agent-instances) for reactive
cleanup and lifecycle details.

Discovery uses Spring Boot's auto-configuration base packages. For interfaces
outside those packages, add `@EnableCopilotAgents(basePackageClasses =
SpringAdvisor.class)` to a configuration class, or specify `basePackages`.
Names default to the decapitalized interface simple name; explicit names must
be unique and contain letters, digits, dots, underscores, or hyphens, starting
with a letter. Conflicts with other beans fail rather than overwriting them.

### Instructions and SDK configuration

| Annotation attribute | Behavior |
| --- | --- |
| `instructions` | Inline system-message content; defaults to appending to the built-in instructions. |
| `instructionsResource` | UTF-8 Spring resource, such as `classpath:/agents/advisor.txt`; mutually exclusive with inline content. Missing/empty resources fail initialization. |
| `instructionsMode` | SDK `SystemMessageMode.APPEND` (default) or `REPLACE`; replacement requires non-empty content. |
| `systemMessage` | Name of a `SystemMessageConfig` bean. Owns its mode and sections; cannot be combined with the preceding instruction attributes. |
| `model`, `reasoningEffort` | Optional SDK settings; omitted values retain runtime defaults. Reasoning support depends on the model. |
| `permissionHandler` | Name of an explicit SDK `PermissionHandler` bean; defaults to denial. |
| `tools` | Names of SDK `ToolDefinition` beans or Spring services with SDK `@CopilotTool` methods. Does not grant tool permissions or disable built-in tools. |
| `listeners` | Names of `CopilotAgentListener` beans attached to each invocation's session. |
| `customizers` | Names of `CopilotAgentCustomizer` beans for advanced SDK configuration. |

Annotation string values support Spring property placeholders. Resource contents
are loaded verbatim. For `CUSTOMIZE`, use a normal Spring bean, preserving the
SDK's section operations and transform callbacks:

```java
@Bean
SystemMessageConfig advisorInstructions() {
    return new SystemMessageConfig()
            .setMode(SystemMessageMode.CUSTOMIZE)
            .setSections(Map.of(SystemMessageSections.TONE,
                    new SectionOverride().setAction(SectionOverrideAction.REPLACE)
                            .setContent("Be concise and use Java examples.")))
            .setContent("Focus on Spring Boot applications.");
}
```

Reference it with `@CopilotAgent(systemMessage = "advisorInstructions")`.
`APPEND` retains the built-in system message; `REPLACE` replaces that message;
`CUSTOMIZE` selectively modifies named sections while retaining the others.
`SectionOverride.setTransform(...)` accepts the SDK's asynchronous transformation
callback. These instruction settings do not replace permission enforcement.
Treat shared configuration beans as immutable after initialization.

Listeners receive `CopilotAgentContext` (agent name, application conversation key,
and SDK session ID) and the native SDK `SessionEvent`. They are scoped to the
current session, not published globally through Spring events. Shared listener
beans must be thread-safe and non-blocking; do not wait inside a callback for
another invocation on the same conversation. Listener exceptions are logged,
other observers continue, and an otherwise successful invocation fails after
cleanup if an observer failed. This layer does not guarantee delivery of events
emitted before subscription or join callbacks already in flight during closing.

Customizers have separate `customize(context, SessionConfig)` and
`customize(context, ResumeSessionConfig)` overloads. Override both when settings
must apply to creation and resumption. They run after declarative settings;
changing the managed session ID or removing the permission handler is rejected.
The SDK remains available directly for structured responses or other contracts
beyond the supported agent return types.

### Tools from Spring services

Use the SDK's existing annotations, not a parallel Spring-specific tool language:

```java
@Service
public class ReferenceTools {
    @CopilotTool("Look up a short local definition")
    public String describeTerm(@CopilotToolParam("Term to describe") String term) {
        return switch (term) {
            case "dependency injection" -> "Dependencies are supplied rather than constructed inside an object.";
            default -> throw new IllegalArgumentException("Unknown reference term");
        };
    }
}
```

Select this bean with `@CopilotAgent(tools = "referenceTools")`. Individual
`ToolDefinition` beans and annotated service beans can be mixed. The SDK's
generated metadata supplies schemas, argument conversion, and executable
handlers. Duplicate tool names within the same namespace fail agent
initialization; separate namespaces remain independent.

**The SDK's `CopilotToolProcessor` must run in the module containing the tool
methods.** Its generated `$$CopilotToolMeta` companion is required; the
integration does not silently fall back to a different reflection/schema engine.
For example, add this compiler configuration, retaining any other processors
your application needs:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessors>
            <annotationProcessor>com.github.copilot.tool.CopilotToolProcessor</annotationProcessor>
        </annotationProcessors>
    </configuration>
</plugin>
```

The SDK must be on the processor path/classpath; the example module demonstrates
this configuration. Annotation-based tool binding is experimental in SDK 1.0.24.
If you also run its experimental-API checker, follow the SDK's opt-in instructions.

Generated handlers bind to the **Spring bean/proxy**, never an unwrapped target,
so transactions and security advice still execute. Class-based proxies support
class or interface tool declarations; tool methods must be overridable.
JDK-proxied services must expose annotated methods on their interface, with
metadata generated for that interface. Unsupported proxy contracts fail
explicitly. Tool registration does not grant permission; SDK flags such as
`skipPermission` are preserved rather than silently changed by the starter.
Preserving advice does not automatically propagate a caller's Spring Security
context into SDK callback threads. Resolve authorized identity explicitly in
your permission/tool policy; do not treat a conversation key as authentication.

### Per-agent execution settings

```yaml
copilot:
  agents:
    spring-advisor:
      model: ${COPILOT_MODEL}
      reasoning-effort: high
      response-timeout: 60s
```

External `model` and `reasoning-effort` values override annotation values.
`response-timeout` defaults to `60s` and must be at least `1ms`. It bounds the
SDK response wait, not queueing, connection/session lookup, creation/resumption,
or cleanup. Errors and timeouts fail the returned future. Cancelling that future
does not promise to abort runtime work; the invocation retains its session and
serialization slot until its actual work and cleanup finish.

Each invocation derives a stable SDK session ID from the agent interface,
agent name, and application conversation key. An O(1) SDK metadata lookup selects
create or resume; lookup failures propagate and never silently start a new
conversation. The integration attaches listeners, awaits a final assistant text
response, unsubscribes, and closes the session attachment before completing the
future. It does not keep an idle session cache or delete conversation history.
The runtime must support SDK 1.0.24 metadata lookup, custom IDs, and persistence
in the same backing store for resumption to work.

Calls for the same conversation are serialized within this application context;
different conversations can run concurrently. Pending bookkeeping is removed
after completion, including failures. Persistence and retention belong to the
runtime. Renaming the interface or agent changes the derived identity. Coordinate
ownership across application instances yourself; serialization is not a
distributed lock. Scope conversation keys to your application and authorized
user/tenant: identifiers and their hashes are not an authorization mechanism.

### Bounded execution

Limits apply across all typed agents in an application context:

```yaml
copilot:
  execution:
    max-concurrent-invocations: 8
    max-outstanding-invocations: 64
```

These are the defaults. Concurrency must be positive; the outstanding limit must
be at least the concurrency limit. Outstanding work includes running invocations,
work waiting for a concurrency slot, and work serialized behind another call to
the same conversation. Admission is non-blocking: once full, calls receive
`RejectedExecutionException` without contacting the SDK.

Invocations retain their admission reservation through cleanup. Queued
cancellations are skipped when observed before SDK work begins, but reservations
are released only when bookkeeping retires, preventing cancellation churn from
creating an unbounded hidden queue. Cancelling running work does not imply a
runtime abort. Shutdown rejects new work, waits for already executing SDK work
and cleanup, and rejects queued work that has not begun its SDK operation. It does
not introduce a hard shutdown deadline independent of SDK behavior.

### Micrometer observations

Add Spring Boot Actuator (or provide Micrometer registries yourself) to activate
instrumentation; it is optional in the basic starter:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

The Micrometer observation and core libraries must be present; a `MeterRegistry`
bean is optional. With an `ObservationRegistry`, accepted calls produce
`copilot.agent.invocation` observations, including queue wait, SDK work, and
cleanup. Tool handlers produce child `copilot.agent.tool` observations around
their returned futures. Standard Micrometer observation handlers supply timers
and tracing. A caller's parent observation is captured before work moves onto a
virtual thread, and tool observations explicitly retain the invocation parent.
This does not install global Reactor context-propagation hooks.

With a `MeterRegistry`, SDK `AssistantUsageEvent` input/output and cache token
counts become `copilot.agent.tokens` counters, tagged by `agent`, `model`, and
`kind` (`input`, `output`, `cache-read`, `cache-write`). Duplicate event IDs within
an invocation are ignored; unknown counts are not invented. These are reported
token-usage counters, not a billing ledger or measurements of context-window
occupancy. Admission failures increment `copilot.agent.rejections`.

Observation tags use bounded configuration identities (`agent`, requested
`model`, qualified `tool`) and `outcome` (`success`, `error`, `timeout`, or
`cancelled`). The integration does not explicitly record prompts, responses,
conversation keys, session IDs, or tool arguments as tags. SDK exceptions remain
errors on observations and may require application-specific redaction.

Set `copilot.observations.enabled=false` to disable the built-in instrumentation,
or supply a `CopilotAgentTelemetry` bean to replace it. No registry is required
for ordinary agent use, and instrumentation does not start a lazy SDK client,
add network health probes, or expose an HTTP management server.

## Reactive event streaming

Add the separate module instead of introducing Reactor into every starter user:

```xml
<dependency>
    <groupId>io.github.brunoborges</groupId>
    <artifactId>copilot-spring-boot-reactor</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

It brings the basic starter and Reactor, but no web framework. Agent interfaces
can return native SDK events with their agent/conversation/session context:

```java
@CopilotAgent(name = "streaming-advisor", instructions = "Explain Spring Boot concisely.")
public interface StreamingAdvisor {
    Flux<CopilotAgentEvent> stream(
            @ConversationId String conversationId,
            @UserMessage String question);
}
```

Streams are **cold**: constructing a `Flux` does not contact the SDK. Each
subscription starts a separate invocation, subject to the same admission limits,
conversation serialization, permissions, instructions, and cleanup rules.
Multiple subscriptions are not automatically shared or replayed. The integration
requests SDK streaming on both session creation and resumption.

SDK events are forwarded in observed order; successful completion is emitted
after SDK idle and session cleanup, even for a successful turn without a final
assistant text message. Scalar text methods still require a text result.
Errors become stream errors. Delivery starts when the
session subscription attaches, not before session creation. A slow subscriber
gets a bounded buffer, defaulting to:

```yaml
copilot:
  reactor:
    buffer-capacity: 256
```

Capacity must be positive and applies per subscription to the adapter's queue,
not to additional buffers introduced by downstream Reactor operators. Overflow
fails with `CopilotAgentStreamOverflowException` after already buffered events
drain; events are not silently dropped. Overflow and subscriber cancellation stop
delivery and cancel the invocation result future. **They do not promise to abort
work already running in the Copilot runtime.** Resources and execution capacity
remain owned until the actual SDK operation and cleanup complete.
Configured agent listeners and usage instrumentation can continue observing
that work after the reactive subscriber has cancelled.

For text deltas, filter the native SDK event type rather than concatenating both
deltas and final messages:

```java
advisor.stream(conversationId, question)
        .map(CopilotAgentEvent::event)
        .ofType(AssistantMessageDeltaEvent.class)
        .map(delta -> delta.getData().deltaContent());
```

Use ordinary WebFlux/SSE adapters in an application if needed; this module does
not add a controller, authorize users, or equate downstream demand with runtime
generation backpressure. SDK callbacks and downstream subscribers should avoid
blocking; offloading operators introduce their own scheduling/buffering policies.

## Multi-session example

The [`copilot-spring-boot-example`](copilot-spring-boot-example) module uses the
starter as a consumer. Its default mode keeps the raw SDK example, without a
session factory or application-wide event broadcasting; `--agent` demonstrates
the routed typed interface above, `--stream` demonstrates its Reactor
counterpart, and `--instance` demonstrates generated bound identities.

`MultiSessionRunner` starts two conversations concurrently on Java 21 virtual
threads. Each call to the injected `ConversationService` creates a fresh SDK
session and registers its own `AssistantMessageEvent` callback before sending a
prompt. The callbacks capture different conversation IDs and delegate to the
shared, stateless `ConversationOutput` Spring service. Output includes both
the application conversation label and the actual SDK session ID, so replies
remain attributable even when they arrive interleaved.

`DenyPermissions` is an injected SDK `PermissionHandler` that explicitly rejects
every permission request it receives. This is not a sandbox or a guarantee that
every runtime operation requires permission; use a runtime and working directory
appropriate for an example.

Each conversation owns its subscription and session through try-with-resources:
it unsubscribes before closing the session, including on registration, send, or
response failure. The service never closes the shared client. The runner waits
for both attempts, propagates failure, and the application closes its Spring
context afterward, releasing the client. The example relies on the SDK's
response timeout (60 seconds in the pinned release); closing a session detaches
this client and does not delete persisted conversation data.

Build the executable JAR and its dependencies from the repository root:

```sh
./mvnw --batch-mode --no-transfer-progress -pl copilot-spring-boot-example -am package
```

Display usage without opening sessions or making model requests:

```sh
java -jar copilot-spring-boot-example/target/copilot-spring-boot-example-0.1.0-SNAPSHOT.jar --help
```

To run the two default prompts, first install and authenticate the CLI as
described in [Managed local runtime](#managed-local-runtime), then run:

```sh
java -jar copilot-spring-boot-example/target/copilot-spring-boot-example-0.1.0-SNAPSHOT.jar \
  --copilot.cli-path=/absolute/path/to/copilot
```

Or provide exactly two quoted prompts:

```sh
java -jar copilot-spring-boot-example/target/copilot-spring-boot-example-0.1.0-SNAPSHOT.jar \
  --copilot.cli-path=/absolute/path/to/copilot \
  "Explain dependency injection without using tools." \
  "Explain virtual threads without using tools."
```

The same application accepts the starter's external-server properties instead
of `cli-path`; the raw-TCP security considerations above still apply. The runtime
selects its default model. **Running the conversations makes real model requests
and consumes your account's Copilot usage.** Assistant message content is written
to the application log. The module's tests use mocks and require neither
credentials nor a running Copilot runtime.

To exercise the typed `SpringAdvisor` instead of the raw SDK service:

```sh
java -jar copilot-spring-boot-example/target/copilot-spring-boot-example-0.1.0-SNAPSHOT.jar \
  --copilot.cli-path=/absolute/path/to/copilot --agent
```

This mode uses a `CUSTOMIZE` system-message bean, the explicit denial handler,
and the same output service as a session-scoped listener. It starts two
independent conversations, then resumes the first for a follow-up: **three
top-level prompt submissions**. The runtime can perform additional model/tool
iterations. A fresh run identifier avoids accidentally resuming a
previous example run. Per-agent settings can also be supplied through arguments
such as `--copilot.agents.spring-advisor.response-timeout=30s`.

Both advisor interfaces opt into the `ReferenceTools` Spring service. Its
SDK-generated local lookup tool is registered without bypassing the explicit
denial handler; default prompts still request answers without tool use. The
example includes Actuator to activate observations, but remains a non-web
application with no HTTP endpoints. Production metric export requires the
appropriate Micrometer backend configuration.

To stream two independent conversations (**two top-level prompt submissions**):

```sh
java -jar copilot-spring-boot-example/target/copilot-spring-boot-example-0.1.0-SNAPSHOT.jar \
  --copilot.cli-path=/absolute/path/to/copilot --stream
```

The runner logs event types and the existing listener logs final assistant
content. `--agent`, `--stream`, and `--instance` are mutually exclusive; `--help` opens no
sessions. Structured responses, declarative MCP/hooks, and attachment adapters
are not part of these three additions.

For application-owned bound agents:

```sh
java -jar copilot-spring-boot-example/target/copilot-spring-boot-example-0.1.0-SNAPSHOT.jar \
  --copilot.cli-path=/absolute/path/to/copilot --instance
```

This creates two independent `BoundAdvisor` handles without passing IDs into
message methods. It closes both after their first responses, then strictly
resumes the first generated key for a follow-up: three top-level prompt
submissions, with underlying model/tool work controlled by the runtime.

## Building

Install JDK 21 and set `JAVA_HOME` to its installation directory. A separate Maven
installation is not required: the committed Maven Wrapper uses Maven 3.9.15.
The first build requires network access to download Maven and dependencies.

On macOS or Linux:

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

On Windows:

```bat
mvnw.cmd --batch-mode --no-transfer-progress verify
```

The build tests property binding, Boot discovery and metadata, conditional
beans, local/server configuration, eager startup errors and cleanup, and
shutdown ownership without running a real runtime or making model requests.
It also verifies the example's per-session routing, concurrent execution,
permission denial, and failure cleanup, plus typed agent discovery, contract
validation, instruction modes, conversation resumption and serialization. Local
protocol stubs exercise the real SDK without paid requests. The example is packaged as an executable
JAR while the integration modules remain ordinary library JARs. GitHub Actions
runs the same build on Java 21 for pushes and pull requests, and supports manual runs.

## License

[MIT](LICENSE).
