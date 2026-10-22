# API and Configuration Reference

This reference describes the APIs and configuration implemented in this
repository. For a guided introduction and runnable examples, see
[README.md](README.md).

| Component | Baseline |
| --- | --- |
| Java | 21 |
| Spring Boot | 4.1.1 |
| Copilot Java SDK | 1.0.24 |
| Integration coordinates | `io.github.brunoborges`, version `0.1.0-SNAPSHOT` |

The integration delegates agent execution, model interaction, tools, and
conversation persistence to the SDK/runtime. It does not implement another
planning loop. Model requests require appropriate authentication and entitlement
and consume Copilot usage.

## Table of contents

- [Modules and installation](#modules-and-installation)
- [Quick start](#quick-start)
- [SDK client API](#sdk-client-api)
- [Agent annotations](#agent-annotations)
- [Bound agent instances](#bound-agent-instances)
- [Conversation lifecycle](#conversation-lifecycle)
- [System instructions](#system-instructions)
- [Tool beans](#tool-beans)
- [Listeners and event types](#listeners-and-event-types)
- [Session customizers](#session-customizers)
- [Configuration reference](#configuration-reference)
- [Client properties](#client-properties)
- [Per-agent properties](#per-agent-properties)
- [Execution properties](#execution-properties)
- [Observation properties](#observation-properties)
- [Reactor properties](#reactor-properties)
- [Configuration examples and precedence](#configuration-examples-and-precedence)
- [Reactive streaming API](#reactive-streaming-api)
- [Observability](#observability)
- [Extension SPIs](#extension-spis)
- [Auto-configuration and bean overrides](#auto-configuration-and-bean-overrides)
- [Threading, errors, and cancellation](#threading-errors-and-cancellation)
- [Security and privacy](#security-and-privacy)
- [Example application](#example-application)
- [Troubleshooting](#troubleshooting)
- [Scope and source references](#scope-and-source-references)

## Modules and installation

| Artifact | Role |
| --- | --- |
| `copilot-spring-boot-parent` | Maven parent and reactor. |
| `copilot-spring-boot-autoconfigure` | Client configuration, agent API, discovery, execution, tool binding, and optional observations. Its SDK dependency is optional. |
| `copilot-spring-boot-starter` | Dependency-only starter supplying the SDK, auto-configuration, and Spring Boot starter. |
| `copilot-spring-boot-reactor` | Optional Reactor return-type adapter; includes the basic starter and Reactor, not a web framework. |
| `copilot-spring-boot-example` | Executable non-web example of raw sessions, typed agents, tools, observations, and streaming. |

No integration artifacts have been published. Install the reactor locally before
using its snapshot dependencies in another application:

```sh
./mvnw --batch-mode --no-transfer-progress install
```

For asynchronous text agents and native SDK access:

```xml
<dependency>
    <groupId>io.github.brunoborges</groupId>
    <artifactId>copilot-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

For reactive agent methods, add `copilot-spring-boot-reactor` instead of, or
alongside, the basic starter. For automatic Micrometer setup, add Spring Boot
Actuator. Neither Reactor nor Actuator is required by the basic starter.
Spring Framework can itself bring Micrometer's observation API transitively;
that does not by itself enable this integration's Micrometer instrumentation.

The starter does not install a Copilot executable or select a platform binary.
For local use, configure `copilot.cli-path`, set the SDK's `COPILOT_CLI_PATH`
environment variable, or supply an appropriate SDK runtime classifier artifact.
For example:

```xml
<dependency>
    <groupId>com.github</groupId>
    <artifactId>copilot-sdk-java-runtime</artifactId>
    <version>1.0.24</version>
    <classifier>linux-x64</classifier>
</dependency>
```

Choose the classifier for the deployment platform. Authenticate the CLI using
SDK/CLI-supported authentication before making model requests. An external
server is another option; see [Client properties](#client-properties).

## Quick start

Define a public interface under the Spring Boot application's base package:

```java
package example.agents;

import java.util.concurrent.CompletableFuture;
import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(
        name = "spring-advisor",
        instructions = "Explain Spring Boot concepts concisely.")
public interface SpringAdvisor {
    CompletableFuture<String> answer(
            @ConversationId String conversationId,
            @UserMessage String question);
}
```

Inject `SpringAdvisor` into an ordinary Spring service using constructor
injection. No implementation class is required. For example, from application
code with an injected `advisor`:

```java
var first = advisor.answer("tenant-a/user-42", "What is dependency injection?");
var followUp = first.thenCompose(answer ->
        advisor.answer("tenant-a/user-42", "Summarize your previous answer."));
```

Calls for the same agent and conversation key share persisted conversation
history. Different keys identify independent conversations. The default agent
permission handler rejects permission requests; it does not silently approve
tools.

For business methods without a conversation-key argument, use
[bound agent instances](#bound-agent-instances) instead of the routed form above.

```yaml
copilot:
  cli-path: /absolute/path/to/copilot
  agents:
    spring-advisor:
      response-timeout: 60s
```

Startup creates the client bean but does not connect by default. The first SDK
operation, including an agent invocation, initiates the connection.

## SDK client API

The starter supplies these native SDK beans:

| Bean name | Type | Behavior |
| --- | --- | --- |
| `copilotClientOptions` | `com.github.copilot.rpc.CopilotClientOptions` | Maps supported client properties unless an options or client bean replaces that configuration. |
| `copilotClient` | `com.github.copilot.CopilotClient` | Shared connection owner, closed by Spring when supplied by the starter. |

Use the SDK directly when the typed agent contract is not appropriate:

```java
import com.github.copilot.CopilotClient;
import org.springframework.stereotype.Service;

@Service
public class RuntimeProbe {
    private final CopilotClient client;

    public RuntimeProbe(CopilotClient client) {
        this.client = client;
    }

    public java.util.concurrent.CompletableFuture<?> ping() {
        return client.ping("hello");
    }
}
```

`CopilotClient`, `CopilotSession`, `SessionConfig`, `ResumeSessionConfig`,
`SystemMessageConfig`, and `ToolDefinition` are SDK APIs, not reimplementations
provided by this project. Consult the
[Java SDK documentation](https://github.com/github/copilot-sdk/tree/main/java)
for their complete contracts.

Close sessions and subscriptions created directly by application code. Do not
close the shared injected client after each request.

## Agent annotations

The application-facing annotations are in
`io.github.brunoborges.copilot.agent`.

### CopilotAgent

`@CopilotAgent` is a runtime annotation on an interface. Explicit-ID interfaces
produce singleton routing proxies. ID-less interfaces register validated agent
definitions, not singleton business proxies; use `CopilotAgents` to obtain a
conversation-bound instance. Both forms share the same declarative settings.

| Attribute | Type | Default | Contract |
| --- | --- | --- | --- |
| `name` | `String` | `""` | Empty derives a JavaBeans-decapitalized interface simple name, such as `SpringAdvisor` to `springAdvisor`. Explicit names must match `[A-Za-z][A-Za-z0-9._-]*` and be unique. |
| `instructions` | `String` | `""` | Inline system-message content. Mutually exclusive with `instructionsResource` and `systemMessage`. |
| `instructionsResource` | `String` | `""` | UTF-8 Spring resource location. Missing or blank content fails initialization. |
| `instructionsMode` | SDK `SystemMessageMode` | `APPEND` | Inline/resource instructions support `APPEND` or `REPLACE`. Use a `systemMessage` bean for `CUSTOMIZE`. |
| `systemMessage` | `String` | `""` | Name of a `SystemMessageConfig` bean. The bean owns the mode; do not combine it with instruction content/resource attributes or a non-default `instructionsMode`. |
| `model` | `String` | `""` | Optional SDK model. Empty leaves selection to the runtime. A per-agent property overrides this value. |
| `reasoningEffort` | `String` | `""` | Optional model-specific reasoning effort. A per-agent property overrides this value. |
| `permissionHandler` | `String` | `""` | Name of an SDK `PermissionHandler` bean. Empty selects the integration's rejection handler, not an arbitrary handler found by type. |
| `tools` | `String[]` | `{}` | Explicit tool bean names: individual `ToolDefinition` objects or SDK-annotated Spring services. |
| `listeners` | `String[]` | `{}` | Names of `CopilotAgentListener` beans, invoked in listed order for each attached session's events. |
| `customizers` | `String[]` | `{}` | Names of `CopilotAgentCustomizer` beans, applied in listed order. |

Annotation string values support Spring property placeholders. Whitespace-only
values are invalid; empty strings on optional attributes mean unset. Instruction
resource contents are loaded verbatim, not evaluated as a template.

The name is the Spring/configuration identity. It does not select an SDK custom
agent file or change the CLI's agent profile automatically. References and
instruction resources are resolved at agent initialization, not refreshed for
every invocation. Treat configuration beans as immutable after initialization.

### ConversationId and UserMessage

Both are runtime parameter annotations with no attributes:

| Annotation | Meaning |
| --- | --- |
| `@ConversationId` | Application-defined conversation key. Not the SDK session ID and not an authorization credential. |
| `@UserMessage` | Message text passed verbatim to the SDK. No prompt-template expansion is performed. |

Choose one method form for an entire interface:

| Form | Parameters | Consumption |
| --- | --- | --- |
| Routed | Two `String` parameters, one `@ConversationId` and one `@UserMessage`, in either order. | Inject the singleton routing bean. |
| Bound | One `String` parameter annotated `@UserMessage`. | Obtain a handle through `CopilotAgents.create` or `resume`. |

Do not mix the two forms in one interface. Null/blank message values, or invalid
explicit conversation keys, fail without SDK work. Omitting `@ConversationId`
does not opt into a hidden global conversation.

| Return type | Requirement | Result |
| --- | --- | --- |
| `CompletableFuture<String>` | Basic starter | Final assistant text; no assistant text is an error. |
| `Flux<CopilotAgentEvent>` | Reactor module | Native events, followed by completion or error after the SDK operation and cleanup. |
| Another type | A matching application return-type adapter | Defined by that adapter; unsupported types fail initialization. |

Raw, wildcard, or unresolved generic variants are not automatically supported
by the built-in adapters. Default interface methods execute locally. Ordinary
proxy `equals`, `hashCode`, and `toString` do not contact the SDK.

### EnableCopilotAgents

Boot applications automatically discover directly annotated interfaces in
their auto-configuration base packages. To include other packages:

```java
@Configuration(proxyBeanMethods = false)
@EnableCopilotAgents(basePackageClasses = SpringAdvisor.class)
class AgentScanning {
}
```

| Attribute | Type | Default | Behavior |
| --- | --- | --- | --- |
| `basePackages` | `String[]` | `{}` | Package names to scan; property placeholders are supported. |
| `basePackageClasses` | `Class<?>[]` | `{}` | Type-safe markers whose packages are scanned. |

Both lists can be combined. With both empty, explicit enabling uses the
importing configuration's package. Scanning does not overwrite conflicting
bean names. Use `@CopilotAgent` directly on interfaces; it is not an ordinary
component stereotype or a supported composed-annotation mechanism.

## Bound agent instances

`CopilotAgents` and `CopilotAgentInstance<T>` are application-facing APIs in
`io.github.brunoborges.copilot.agent`. Inject the `CopilotAgents` Spring bean:

```java
@CopilotAgent(name = "advisor", instructions = "Explain Spring Boot concisely.")
public interface Advisor {
    CompletableFuture<String> answer(@UserMessage String question);
}
```

From application code with an injected `agents` provider:

```java
String savedKey;
try (var instance = agents.create(Advisor.class)) {
    var advisor = instance.agent();
    advisor.answer("What is dependency injection?").join();
    advisor.answer("Show an example of that.").join();
    savedKey = instance.conversationId();
}

try (var resumed = agents.resume(Advisor.class, savedKey)) {
    resumed.agent().answer("Continue our discussion.").join();
}
```

| API | Contract |
| --- | --- |
| `<T> CopilotAgentInstance<T> create(Class<T> agentType)` | Bind a fresh generated UUID conversation key. The type must be a discovered ID-less definition. |
| `<T> CopilotAgentInstance<T> resume(Class<T> agentType, String conversationId)` | Bind an existing application conversation key. Blank keys fail immediately; history existence is checked strictly on invocation. |
| `T CopilotAgentInstance.agent()` | Return this handle's bound business proxy while open; throws `IllegalStateException` after closing begins. |
| `String CopilotAgentInstance.conversationId()` | Optional metadata for saving/resuming identity. This is the application key, not the derived SDK session ID, and remains readable after close. |
| `void CopilotAgentInstance.close()` | Reject new work and wait for already admitted operations and cleanup. Does not close the shared client or delete history. |

Creating or resuming a handle does not itself issue SDK requests. There is no
underlying persisted conversation to resume until an operation has opened a
session. Strict resume of missing history fails the operation with
`CopilotConversationNotFoundException`; it never silently creates an empty
conversation. Once a newly created handle has opened a session, its later calls
also require that history to exist, even if the earlier model response failed.
Metadata lookup errors continue to propagate without a fallback.

Separate `create(...)` calls produce separate proxies and conversation keys.
Repeated calls on one handle preserve history. `resume(...)` produces a new
handle/proxy for the saved identity, not the original Java object. Multiple
handles targeting the same agent/key share history and the existing local
serialization rules, but closing one does not close the others.

ID-less interfaces are intentionally not injectable as singleton agent beans.
Definitions are managed by Spring; runtime handles are application-owned,
not prototype beans requiring implicit destruction. The bound business proxy is
not itself registered as a Spring bean. Keep application authorization and
transaction boundaries in Spring-managed services; tool service proxies retain
their normal Spring advice.

### Closing and asynchronous work

Each method still creates/resumes and detaches an SDK session attachment per
invocation. A handle owns identity and operation lifetime, not an idle live
`CopilotSession` or the shared `CopilotClient`.

Closing is idempotent and blocking. It preserves interrupt status and continues
waiting for cleanup; there is no separate close timeout or implicit runtime
abort. Cancellation of a returned future does not make unfinished SDK work
disappear from this barrier. Closed cached proxies reject later invocations;
an already constructed cold `Flux` fails if subscribed after its handle closes.

Await the entire intended workflow before leaving a try-with-resources block.
`close()` waits for operations already invoked, not future continuations that
have not submitted their next call, or arbitrary downstream event processing.
It does not cancel subscribers or drain their buffers.

Never block in `close()` from SDK listeners, permission/tool/customizer callbacks,
or other work needed to finish the same invocation. Common synchronous paths
are guarded and reject self-closing rather than deadlocking; arbitrary
application asynchronous callbacks are not automatically context-propagated.

ID-less reactive methods are supported with the optional module. For asynchronous
resource cleanup, offload blocking close rather than closing on an event loop or
SDK callback:

```java
Flux<CopilotAgentEvent> events = Flux.usingWhen(
        Mono.fromSupplier(() -> agents.create(MyStreamingAdvisor.class)),
        instance -> instance.agent().stream(question),
        instance -> Mono.fromRunnable(instance::close)
                .subscribeOn(Schedulers.boundedElastic()));
```

Here `MyStreamingAdvisor` declares an ID-less
`Flux<CopilotAgentEvent> stream(@UserMessage String question)` method; `Mono`,
`Flux`, and `Schedulers` are Reactor types. Cancellation cleanup can still wait
for already-running SDK work. Close is not a guarantee of billing cancellation.

No new configuration keys are needed for instances. They inherit their
definition's model, instructions, tools, listeners, timeout, observations, and
global execution bounds. Keep the routed form when application services prefer
passing an authorized conversation key per call.

## Conversation lifecycle

For a normal invocation, the integration:

1. Validates the conversation key/message and tries to reserve admission.
2. Serializes behind earlier work for the same agent/conversation.
3. Waits for a global execution slot.
4. Derives a stable SDK session identity from the interface's fully qualified
   name, logical agent name, and conversation key.
5. Uses SDK `getSessionMetadata` to select create or resume. A documented
   null/not-found result selects creation for routed calls or a new handle's
   initial session; strict bound resumption fails instead. Lookup errors propagate.
6. Applies agent settings, permissions, tools, and create/resume customizers.
7. Attaches session listeners, sends the message, and awaits the SDK response.
8. Unsubscribes and closes the session attachment.
9. Releases execution/admission bookkeeping and completes the result.

There is no idle session cache, implicit default conversation, automatic retry,
or automatic history deletion. Persisted history belongs to the runtime.
Changing an interface's fully qualified name or logical agent name changes the
derived identity. Do not depend on the exact digest encoding as an application
API; listeners expose the actual SDK session ID when needed.

All methods on the same agent use the same conversation namespace. Serialization
is local to this application context, not a distributed lock. Coordinate
ownership across replicas and scope keys to authorized users/tenants yourself.
Resumption requires a runtime supporting the pinned SDK's metadata lookup,
custom IDs, and persistence in the same backing store.

## System instructions

| SDK mode | Meaning | Configuration path |
| --- | --- | --- |
| `APPEND` | Retain the built-in system message and append content. | Inline instructions, resource, or SDK bean. |
| `REPLACE` | Replace the built-in system-message text. | Non-empty inline/resource content or SDK bean. |
| `CUSTOMIZE` | Override named sections while retaining others. Optional content is appended after sections. | SDK `SystemMessageConfig` bean. |

For resource-backed instructions:

```java
@CopilotAgent(
        name = "spring-advisor",
        instructionsResource = "classpath:/agents/advisor.txt",
        instructionsMode = SystemMessageMode.APPEND)
```

For section customization, define a bean inside a Spring configuration class:

```java
@Bean
SystemMessageConfig advisorInstructions() {
    return new SystemMessageConfig()
            .setMode(SystemMessageMode.CUSTOMIZE)
            .setSections(Map.of(
                    SystemMessageSections.TONE,
                    new SectionOverride()
                            .setAction(SectionOverrideAction.REPLACE)
                            .setContent("Be concise and use Java examples.")))
            .setContent("Focus on Spring Boot applications.");
}
```

Reference it with `@CopilotAgent(systemMessage = "advisorInstructions")`.
The SDK types in this example are `com.github.copilot.SystemMessageMode` and
`com.github.copilot.rpc.SystemMessageConfig`, `SystemMessageSections`,
`SectionOverride`, and `SectionOverrideAction`; `Map` is `java.util.Map`.

Section actions include replace, append, prepend, remove, preserve, and
transform. A transform can inspect the current section rather than reconstruct
it:

```java
new SectionOverride().setTransform(current ->
        CompletableFuture.completedFuture(current + "\nUse concise examples."));
```

Transform callbacks and configuration beans can be shared across conversations
and must be thread-safe. Use a session customizer for context-dependent
configuration. Instruction replacement is not a permission override or sandbox;
SDK configuration discovery/repository instructions are a separate concern.

## Tool beans

Select tools explicitly through `@CopilotAgent(tools = {"referenceTools"})`.
Unreferenced service methods are not exposed just because they are Spring beans.

| Referenced bean | Binding |
| --- | --- |
| SDK `ToolDefinition` | Registered directly; no annotation processor needed for that definition. |
| Service with SDK `@CopilotTool` methods | Uses SDK-generated schemas, argument conversion, and handlers. |

Example service:

```java
import com.github.copilot.tool.CopilotTool;
import com.github.copilot.tool.CopilotToolParam;
import org.springframework.stereotype.Service;

@Service
public class ReferenceTools {
    @CopilotTool(value = "Describe a local Spring concept", name = "describe_term")
    public String describeTerm(@CopilotToolParam("Concept to describe") String term) {
        return switch (term) {
            case "dependency injection" -> "Dependencies are supplied to an object.";
            default -> throw new IllegalArgumentException("Unknown reference term");
        };
    }
}
```

### Annotation processing

Enable the SDK processor in the module compiling the annotated tool methods:

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

Keep any other processors your application needs, and put the SDK on the
processor classpath/path. Generated `$$CopilotToolMeta` companions are required.
Missing metadata fails initialization with a compiler-configuration hint; the
integration does not substitute another schema engine.

Annotation-based tooling is experimental in SDK 1.0.24. If also using the SDK's
experimental-API checker, follow its opt-in requirements. The complete build
configuration is demonstrated in
[the example POM](copilot-spring-boot-example/pom.xml).

### Proxies and permissions

Bindings invoke the Spring bean/proxy, not an unwrapped target:

| Bean form | Requirements |
| --- | --- |
| Plain service | SDK-generated metadata for its declared tool methods or annotated interface. |
| Class-based Spring proxy | Class/interface tool metadata; tool methods must be overridable, not final. |
| JDK Spring proxy | Annotated tool methods must be exposed on an interface compiled with the SDK processor. |

Transactions/security advice therefore still run. This does not automatically
propagate a caller's Spring Security context or request scope into SDK callback
threads.

Names and namespace names must be non-blank. Duplicate tool names within a
namespace fail initialization; different namespaces remain independent.
Metadata and explicit SDK flags, including `skipPermission`, are preserved.
Registering a tool does not itself approve its execution or disable built-in
tools. An empty tool list means no custom tools supplied by this definition,
not a text-only runtime sandbox.

## Listeners and event types

These contracts are in `io.github.brunoborges.copilot.agent`:

| Type | Members |
| --- | --- |
| `CopilotAgentContext` | Record accessors `agentName()`, `conversationId()`, `sessionId()`. |
| `CopilotAgentEvent` | Record accessors `context()` and native SDK `event()`. Used by return-type adapters such as Reactor. |
| `CopilotAgentListener` | `void onEvent(CopilotAgentContext context, SessionEvent event)`. |

Bind listener bean names with `@CopilotAgent(listeners = "agentMessages")`:

```java
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionEvent;
import io.github.brunoborges.copilot.agent.CopilotAgentContext;
import io.github.brunoborges.copilot.agent.CopilotAgentListener;
import org.springframework.stereotype.Component;

@Component("agentMessages")
public class AgentMessages implements CopilotAgentListener {
    @Override
    public void onEvent(CopilotAgentContext context, SessionEvent event) {
        if (event instanceof AssistantMessageEvent message) {
            System.out.println(context.conversationId() + ": " + message.getData().content());
        }
    }
}
```

Callbacks are attached to individual sessions; they are not broadcasts through
Spring's `ApplicationEventPublisher`. Shared listener instances can receive
concurrent calls from different conversations. Use the supplied context rather
than a mutable singleton "current session."

Listed observers continue after a listener exception. Exceptions are logged,
and an otherwise successful invocation fails after cleanup if an observer
failed. Delivery is not guaranteed for events emitted before attachment or for
callbacks already in flight during closing. Listeners must not block waiting
for another invocation on the same serialized conversation.

## Session customizers

`CopilotAgentCustomizer` has two default no-op methods:

```java
void customize(CopilotAgentContext context, SessionConfig config);
void customize(CopilotAgentContext context, ResumeSessionConfig config);
```

Register a bean and select it with `@CopilotAgent(customizers = "workspace")`.
For example, inside a configuration class:

```java
@Bean
CopilotAgentCustomizer workspace() {
    return new CopilotAgentCustomizer() {
        @Override
        public void customize(CopilotAgentContext context, SessionConfig config) {
            config.setWorkingDirectory("/srv/approved-workspace");
        }

        @Override
        public void customize(CopilotAgentContext context, ResumeSessionConfig config) {
            config.setWorkingDirectory("/srv/approved-workspace");
        }
    };
}
```

Override both methods when settings or callbacks must be reapplied on resume.
For each invocation, ordering is:

1. Annotation/property settings, default permissions, and declared tools.
2. Customizers in annotation order.
3. Forced SDK streaming for reactive/event invocations, when applicable.
4. Optional telemetry wrapping of the final tool handlers.
5. Validation that the managed session ID is unchanged and a permission
   handler remains configured.

Customizers can use SDK capabilities not exposed as declarative properties,
such as hooks or MCP settings. They are responsible for the validity of their
advanced SDK configuration; startup duplicate-tool validation covers declared
tool references, not arbitrary lists constructed later by customizers.

## Configuration reference

Configuration uses ordinary Spring Boot property sources: application files,
profiles, environment values, and command-line arguments. Use explicit duration
units such as `30s` or `250ms`.

Only the integration-owned keys below are mapped automatically. Arbitrary
`SessionConfig` fields are not forwarded from similarly named properties.
Use SDK beans/customizers for settings not listed here.

### Client properties

These configure the starter-owned client, not individual conversations.
The binding type is `io.github.brunoborges.copilot.autoconfigure.CopilotProperties`.

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `copilot.enabled` | Boolean | `true` | Enable client and agent integration, including optional adapters/instrumentation. |
| `copilot.eager-start` | Boolean | `false` | Connect during managed client creation instead of first use. |
| `copilot.startup-timeout` | Duration | `30s` | Positive eager connection wait; must fit signed 64-bit nanoseconds. Does not bound subsequent cleanup. |
| `copilot.cli-path` | String | Unset | Explicit local executable; non-blank if supplied. SDK resolves the runtime when omitted. |
| `copilot.cli-args` | List of strings | Empty | Additional local runtime arguments; blank elements are rejected. |
| `copilot.working-directory` | String | Unset | Local process working directory; omitted values retain SDK defaults. Not a per-session working directory. |
| `copilot.log-level` | String | SDK default, `info` | Non-blank, forwarded to SDK/CLI without an integration-specific level enum. |
| `copilot.cli-url` | String | Unset | Existing raw-TCP server address; switches the default options away from stdio. |
| `copilot.tcp-connection-token` | String | Unset | Non-blank external-server token; requires `cli-url`. |

`cli-url` cannot be combined with `cli-path`, non-empty `cli-args`, or
`working-directory`. Supported address forms include `4321`, `localhost:4321`,
`http://localhost:4321`, and `[::1]:4321`. Ports must be 1-65535. URI credentials,
paths (including a trailing `/`), queries, and fragments are rejected.

**SDK 1.0.24 uses raw TCP for external-server connections. Neither `http://`
nor `https://` enables HTTP or TLS.** Use a trusted local connection or a
separately secured tunnel, especially when sending a connection token. The
external server owns its GitHub authentication and process lifecycle.

There is no integration property for a GitHub credential. Local authentication
follows SDK/CLI rules; external secrets should use supported environment or
application secret mechanisms, never committed literals.

### Per-agent properties

Replace `<name>` with the logical `@CopilotAgent` name:

The binding type is `CopilotAgentsProperties`, whose `agents()` map contains
`CopilotAgentsProperties.Agent` records, in the auto-configuration package.

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `copilot.agents.<name>.model` | String | Annotation value, then runtime default | Non-blank override of `model`. |
| `copilot.agents.<name>.reasoning-effort` | String | Annotation value, then runtime default | Non-blank override of `reasoningEffort`; support depends on the selected model. |
| `copilot.agents.<name>.response-timeout` | Duration | `60s` | At least `1ms`, representable in signed 64-bit nanoseconds. Bounds SDK response waiting only. |

Response waiting excludes queueing, connection/session metadata lookup,
creation/resumption, and cleanup. It is not a total request deadline.
Instructions, tools, listeners, and bean references are annotation/bean
configuration, not additional properties under this map.

Explicit lower-case, kebab-case names make property configuration easiest.
For keys containing punctuation that Spring Boot map binding would otherwise
interpret or normalize, use quoted bracket notation:

```yaml
copilot:
  agents:
    "[customer.agent]":
      response-timeout: 30s
```

### Execution properties

These limits are shared by all typed agents in one application context.
The binding type is `CopilotAgentExecutionProperties` in the auto-configuration
package.

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `copilot.execution.max-concurrent-invocations` | Integer | `8` | Positive maximum of invocations performing SDK work, including response waiting and cleanup. |
| `copilot.execution.max-outstanding-invocations` | Integer | `64` | Running plus queued/serialized accepted calls; must be at least the concurrency limit. |

Admission does not block the caller. A full envelope returns a
`RejectedExecutionException` without contacting the SDK. Outstanding counts
include calls serialized behind another call to the same conversation, not
just tasks waiting for a global slot. These are client invocation bounds, not
a guarantee about detached work continuing in the runtime.

### Observation properties

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `copilot.observations.enabled` | Boolean | `true` | Enable built-in Micrometer integration when its classpath/registry conditions are met. Does not disable an application-provided telemetry bean. |

The Micrometer observation and core libraries and an `ObservationRegistry` bean
are required for built-in instrumentation. A `MeterRegistry` bean is optional.
Adding Spring Boot Actuator is the usual way to supply the registries/handlers.
Without activation, ordinary agent use works with no-op telemetry.

### Reactor properties

These apply only when the optional Reactor module is present.
The binding type is `io.github.brunoborges.copilot.reactor.CopilotReactorProperties`.

| Property | Type | Default | Meaning and validation |
| --- | --- | --- | --- |
| `copilot.reactor.buffer-capacity` | Integer | `256` | Positive maximum events buffered in the adapter queue per subscription. |

This is not a bound on buffers introduced by downstream Reactor operators.
There is no separate `copilot.reactor.enabled` property; `copilot.enabled`
controls integration activation.

### Configuration examples and precedence

Example local configuration, including optional groups when those modules are
installed:

```yaml
copilot:
  enabled: true
  cli-path: /absolute/path/to/copilot
  eager-start: false
  startup-timeout: 30s
  agents:
    spring-advisor:
      response-timeout: 60s
      # model: an-available-model-id
      # reasoning-effort: high
  execution:
    max-concurrent-invocations: 8
    max-outstanding-invocations: 64
  observations:
    enabled: true
  reactor:
    buffer-capacity: 256
```

Alternative external-server configuration; do not add the local-only settings:

```yaml
copilot:
  cli-url: localhost:4321
  tcp-connection-token: ${COPILOT_SERVER_TOKEN}
```

Omit the token property if the server does not require one. The placeholder is
an application/environment secret, not a new SDK GitHub authentication property.

Precedence is:

1. Spring Boot resolves property sources using its standard rules.
2. Per-agent `model`/`reasoning-effort` properties override annotation values.
3. Omitted model/reasoning settings leave those choices to SDK/runtime defaults.
4. Customizers run after declarative session settings, subject to the managed
   ID/permission/streaming invariants described above.

For example, `--copilot.agents.spring-advisor.response-timeout=30s` overrides
the value supplied through an application file. Supplying a native client or
options bean changes ownership/mapping as described below.

## Reactive streaming API

Add `io.github.brunoborges:copilot-spring-boot-reactor:0.1.0-SNAPSHOT`.
The basic starter does not include Reactor or a web framework.

```java
import reactor.core.publisher.Flux;
import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotAgentEvent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(name = "streaming-advisor", instructions = "Explain Java concepts.")
public interface StreamingAdvisor {
    Flux<CopilotAgentEvent> stream(
            @ConversationId String conversationId,
            @UserMessage String question);
}
```

Constructing a `Flux` starts no SDK operation. **Each subscription starts a new
invocation** using the normal conversation and admission rules. Repeated
subscriptions are not automatically shared or replayed.

Events contain the native SDK types, including deltas, final messages, tool,
usage, and session events when emitted by the runtime. Successful completion
follows SDK idle and cleanup; a final assistant text message is not required
for an event stream. SDK and cleanup failures become stream errors.

To consume only text deltas:

```java
Flux<String> deltas = advisor.stream(conversationId, question)
        .map(CopilotAgentEvent::event)
        .ofType(com.github.copilot.generated.AssistantMessageDeltaEvent.class)
        .map(delta -> delta.getData().deltaContent());
```

Do not concatenate both deltas and final message content as if they were
independent text fragments. The module does not add an HTTP controller,
SSE endpoint, authorization policy, or global Reactor hooks.

### Buffering and cancellation

Observed SDK events are serialized into a bounded per-subscription queue.
On overflow, `io.github.brunoborges.copilot.reactor.CopilotAgentStreamOverflowException`
is delivered after previously buffered events drain. The exception extends
`IllegalStateException`; its message includes the configured capacity.

Overflow and subscriber cancellation stop delivery to that subscriber and
cancel the local invocation result future. They do not promise to abort
work already running in Copilot. Configured listeners and usage instrumentation
can still observe that work. Session ownership, serialization, and admission
capacity remain held until actual work/cleanup finish.

Downstream demand is not runtime-generation backpressure. Avoid blocking SDK
callbacks; application operators such as `publishOn` introduce their own
scheduling and buffering behavior.

## Observability

Add `org.springframework.boot:spring-boot-starter-actuator` for conventional
Boot Micrometer setup, or provide compatible registries/handlers yourself.
No HTTP management server or network SDK health probe is added by this
integration.

| Observation or meter | Role | Integration-provided tags |
| --- | --- | --- |
| `copilot.agent.invocation` | Accepted call, including queue wait, SDK work, and cleanup. Standard observation handlers supply timers/traces. | `agent`, requested `model`, `outcome` |
| `copilot.agent.tool` | Child observation around the SDK tool handler's returned future. | `agent`, qualified `tool`, `outcome` |
| `copilot.agent.tokens` | Counters from SDK `AssistantUsageEvent` reports. Requires a meter registry. | `agent`, reported `model`, `kind` |
| `copilot.agent.rejections` | Capacity-admission rejection counter. Requires a meter registry. | `agent` |

These are Micrometer base names; exporters can transform names and add their
own tags. An omitted requested model is tagged `runtime-default`. Outcomes are
`success`, `error`, `timeout`, and `cancelled`; an operation failure takes
precedence over a caller-cancelled marker.

Token `kind` values are `input`, `output`, `cache-read`, and `cache-write`.
Only reported positive counts increment counters. Unknown counts are not
invented, negative counts are logged and ignored, and duplicate non-null SDK
event IDs are deduplicated within an invocation. These are usage counters,
not a billing ledger or context-window occupancy measurements.

Parent observations are captured at invocation admission before work moves to
a virtual thread. Tool observations explicitly retain the invocation parent,
including SDK callback execution on another thread. This does not install
automatic Reactor or Spring Security context propagation.

Prompts, replies, conversation keys, session IDs, and tool arguments are not
explicitly recorded as tags. SDK exceptions are recorded as errors and may
need application-specific redaction. Example listeners intentionally log
assistant content; that is separate from built-in telemetry.

## Extension SPIs

The following interfaces are in `io.github.brunoborges.copilot.agent`.
Classes in `agent.internal` and auto-configuration implementation classes are
infrastructure, not application-facing extension contracts.

### Telemetry

Provide a `CopilotAgentTelemetry` bean to replace the built-in implementation.
`CopilotAgentTelemetry.NOOP` is the no-op implementation.

| Method | Contract |
| --- | --- |
| `Call start(String agent, String model)` | Start instrumentation for an accepted invocation, before queue wait. Return a non-null per-call handle. |
| `void rejected(String agent)` | Optional callback for capacity rejection. Default is no-op. |
| `Call.openScope()` | Returns a `CopilotAgentTelemetry.Scope` to bind context while work executes. |
| `Call.tool(String name)` | Create child tool instrumentation. Default returns `Call.NOOP`. |
| `Call.usage(AssistantUsageEvent event)` | Observe native usage events. Default is no-op. |
| `Call.error(Throwable error)` | Record operation failure. Default is no-op. |
| `Call.cancelled()` | Record caller cancellation. Default is no-op. |
| `Call.stop()` | End instrumentation. Make implementations idempotent. |
| `Scope.close()` | Release bound instrumentation context; no checked exception is declared. |

Implementations must be lightweight and thread-safe, and should not throw.
Callbacks can occur on the caller, execution, SDK, or future-completion threads.
Instrumentation failures can fail the invocation; they are not a request to
retry an already executed operation. Shutdown/admission cleanup still applies.

### Return-type adapters

`CopilotAgentReturnTypeAdapter` declares:

```java
boolean supports(java.lang.reflect.Type returnType);
Object adapt(CopilotAgentInvocation invocation);
```

`CopilotAgentInvocation` declares:

```java
CompletableFuture<?> invoke(java.util.function.Consumer<CopilotAgentEvent> events);
```

An adapter supplies an event consumer when ready to start one invocation.
The returned future signals completion/error; adapters must not require a
non-null text result. It does not expose a raw session or grant ownership of
the shared client.

Register adapters as Spring beans. Matching is based on the declared method
return type and must be unique; multiple matches fail initialization rather
than selecting one by `@Order`. Without a matching adapter,
`CompletableFuture<String>` uses the built-in scalar path.

The Reactor module registers `ReactorCopilotAgentAdapter`, matching exactly
`Flux<CopilotAgentEvent>`. To replace that representation with another adapter,
replace the default adapter bean or exclude
`io.github.brunoborges.copilot.reactor.CopilotReactorAutoConfiguration` before
registering a competing implementation. Merely adding an adapter does not
implement SDK structured-response schemas or new session capabilities.

## Auto-configuration and bean overrides

| Configuration class | Activation and backoff |
| --- | --- |
| `CopilotAutoConfiguration` | SDK client class present, `copilot.enabled=true` or absent, and no user `CopilotClient` bean. |
| `CopilotAgentsAutoConfiguration` | SDK/client bean available and integration enabled. Creates the invoker, `CopilotAgents` provider, and no-op telemetry when not otherwise supplied. |
| `CopilotAgentObservationAutoConfiguration` | SDK and Micrometer observation/core classes present, observation registry available, both enable flags active, and no user telemetry bean. |
| `CopilotReactorAutoConfiguration` | Reactor module and SDK/Flux classes present, integration enabled; backs off for a user `ReactorCopilotAgentAdapter`. |

The first three are under `io.github.brunoborges.copilot.autoconfigure`; the
last is under `io.github.brunoborges.copilot.reactor`.

The provider bean is named `copilotAgents` and backs off for an
application-provided `CopilotAgents` implementation. Discovered ID-less
definitions retain their logical bean names as internal definition metadata,
not as singleton business-interface instances.

### Native client options

Providing a `CopilotClientOptions` bean replaces client connection-property
mapping. For example, inside a configuration class:

```java
@Bean
CopilotClientOptions copilotClientOptions() {
    return new CopilotClientOptions()
            .setCliPath("/absolute/path/to/copilot")
            .setLogLevel("debug");
}
```

`eager-start` and `startup-timeout` still control the starter-owned client's
initialization. SDK options such as `autoStart` retain the value you supply;
turning off SDK automatic startup requires an explicit startup strategy.

### Native client ownership

Providing a typed `CopilotClient` bean makes client auto-configuration back off:
it does not create its options bean, start/reconfigure that client, or add its
own cleanup callback. Typed agents can still use the supplied client.

Spring normally infers `close()` as the destroy method on a user `@Bean`.
Disable that inference only when another owner actually manages the client's
lifecycle. Use precise `@Bean` return types so Boot can detect replacement beans
before instantiation.

The starter-owned client is created eagerly as a bean even with global lazy
initialization, but connection remains lazy unless eager startup is requested.
Eager startup failure closes the partially initialized client and fails context
startup. The timeout bounds the connection wait, not all cleanup. Normal client
shutdown delegates to SDK `close()`, whose own internal failures are logged at
Java logging level `FINE` rather than propagated by that SDK API.

## Threading, errors, and cancellation

| Work | Execution context |
| --- | --- |
| Admission and initial observation | Calling thread; capacity checks are non-blocking. |
| Agent SDK operation and cleanup | Java virtual-thread execution, bounded by the shared invocation limits. |
| Tool methods and native event listeners | SDK callback execution; not implicitly a Spring web request scope. |
| Future continuations | Normal `CompletableFuture` rules unless the application supplies an asynchronous executor. |
| Reactive callbacks | SDK/subscription/operator context; use explicit Reactor scheduling when appropriate. |

| Condition | Result |
| --- | --- |
| Invalid signature, duplicate name, missing resource/bean/tool metadata | Initialization failure, normally wrapped by Spring bean creation. |
| Invalid bound configuration | Spring configuration-binding/validation failure before use. |
| Blank/null invocation arguments | Failed scalar future, or reactive error on subscription; no SDK operation. |
| Outstanding limit reached | `RejectedExecutionException`; no SDK operation. |
| SDK lookup/create/resume/send failure | Failure propagated through the asynchronous result; no silent new-conversation fallback. |
| Scalar turn has no assistant text | `IllegalStateException`. Event streams can complete normally without text. |
| Bound resume/history continuation cannot find the session | `CopilotConversationNotFoundException`; no replacement conversation is created. |
| Cached bound proxy used after handle close | Failed invocation; a cold stream errors when subscribed. |
| Listener failure | Logged; other observers continue; otherwise successful invocation fails after cleanup. |
| Response timeout | Failed future/stream; it is not an end-to-end deadline or guaranteed runtime abort. |
| Stream overflow | `CopilotAgentStreamOverflowException` after buffered events drain. |
| Call after invoker shutdown | Failed result; queued work not yet executing SDK operations is rejected. |

Asynchronous causes can be wrapped by standard `CompletionException` or
`ExecutionException` when using `join()` or `get()`. Cleanup failures are not
converted to empty successful replies.

Cancellation does not release a conversation lock or execution reservation
while actual work still owns resources. Queued calls are skipped when
cancellation is observed before SDK work begins; their bookkeeping remains
bounded until retirement. Shutdown rejects new work and waits for active work
and cleanup. There is no independent hard shutdown deadline that overrides SDK
behavior.

## Security and privacy

- Scope conversation keys to the application and authorized user/tenant.
  Session IDs and their hashes are not authorization checks.
- Denying permission requests is not a runtime sandbox or proof that every
  operation will request permission.
- Explicit SDK tool flags are preserved. Never assume registering tools
  implicitly approves them or that a default handler overrides every SDK flag.
- Keep tokens out of annotation literals, committed configuration, and logs.
  Use environment/secret mechanisms appropriate to the application.
- Raw external TCP connections need a trusted network or separately secured
  tunnel; an `https://` spelling does not add TLS.
- Preserving Spring proxy advice does not propagate authentication into SDK
  threads. Resolve trusted identity explicitly in permission/tool policies.
- Decide whether application event listeners may log assistant text or tool
  data. Built-in metric tags deliberately omit those payloads.

## Example application

Build:

```sh
./mvnw --batch-mode --no-transfer-progress -pl copilot-spring-boot-example -am package
```

Display help without opening sessions:

```sh
java -jar copilot-spring-boot-example/target/copilot-spring-boot-example-0.1.0-SNAPSHOT.jar --help
```

| Mode | Behavior | Top-level prompt submissions |
| --- | --- | --- |
| No mode option | Two concurrent raw SDK sessions with separately attached listeners. | Two |
| `--agent` | Two typed-agent conversations, then a follow-up resuming the first. | Three |
| `--stream` | Two independent typed reactive event streams. | Two |
| `--instance` | Two generated bound instances, closed, then a fresh handle strictly resuming the first for a follow-up. | Three |

For a real run, pass `--copilot.cli-path=/absolute/path/to/copilot` or the
supported external-server configuration and authenticate appropriately.
`--agent`, `--stream`, and `--instance` are mutually exclusive. Supply either zero positional
prompts for defaults or exactly two quoted prompts.

The example includes `ReferenceTools`, explicit `DenyPermissions`, a shared
session-context-aware output listener, and a customized system-message bean.
Actuator activates observations, but the application stays non-web with no
HTTP endpoints. Stream mode logs event types; the configured listener logs
final assistant text. These counts describe application prompt submissions,
not a guaranteed number of underlying model calls: the runtime can perform
additional model/tool iterations. Running these modes consumes real Copilot usage.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| No routed agent bean | Put the directly annotated interface under Boot's base package or add `@EnableCopilotAgents`; verify integration is enabled. ID-less definitions are deliberately not singleton business beans. |
| ID-less interface cannot be injected | Inject `CopilotAgents`, then obtain `CopilotAgentInstance<T>` through `create` or `resume`. |
| Bean name collision | Set unique explicit agent names; do not rely on bean overriding. |
| Unsupported method contract | Use supported return types and one consistent form: routed with two annotated strings, or bound with one `@UserMessage` string. Add the Reactor module for `Flux<CopilotAgentEvent>`. |
| Strict resume fails | Reuse the saved application key with the same interface/name/runtime store; merely allocating and closing a handle does not create history. |
| Close blocks or callback self-closing fails | Await workflows before closing, keep blocking close off SDK/event-loop callbacks, and remember cancelled futures can still own SDK work. |
| Missing generated tool metadata | Run `CopilotToolProcessor` in the module declaring the annotated methods, and retain generated classes in the artifact. |
| JDK-proxied service cannot bind tools | Annotate its exposed interface and generate metadata for that interface; do not unwrap it to bypass advice. |
| Local runtime missing | Set an explicit CLI path/environment override or include the matching SDK runtime classifier. |
| External address rejected | Supply an explicit valid port; remove credentials, path/trailing slash, query, and fragment. |
| Repeated calls lose expected context | Reuse the same authorized conversation key, agent name/interface, and persisted runtime backing store. |
| Rejected invocations | Inspect concurrent/outstanding limits and long-running or serialized calls; there is no automatic retry. |
| Missing observations | Add required Micrometer classes/registry or Actuator, check the enable flag and custom telemetry replacement. |
| Missing usage counters | Supply a meter registry and inspect whether the runtime emits usage fields; missing counts are not synthesized. |
| Stream overflows | Consume/offload deliberately, reduce producer work or adjust buffer capacity; remember downstream operators add their own buffers. |
| Cancelled request still consumes usage | Cancellation is not a guaranteed runtime abort; running work remains owned until completion/cleanup. |

## Scope and source references

Implemented declarative contracts are limited to the APIs and keys in this
reference. There is no automatic POJO structured-response agent mapping,
declarative MCP/hook/attachment configuration layer, distributed conversation
lock, user authorization implementation, automatic permission approval, or
automatic CLI installation. Advanced SDK capabilities remain available through
native beans and customizers. This guide does not claim native-image/AOT or
experimental in-process runtime setup support.

Source entry points:

- [Agent API package](copilot-spring-boot-autoconfigure/src/main/java/io/github/brunoborges/copilot/agent)
- [Client and agent property classes](copilot-spring-boot-autoconfigure/src/main/java/io/github/brunoborges/copilot/autoconfigure)
- [Reactor API and configuration](copilot-spring-boot-reactor/src/main/java/io/github/brunoborges/copilot/reactor)
- [Runnable examples](copilot-spring-boot-example/src/main/java/io/github/brunoborges/copilot/example)
- [Additional configuration metadata](copilot-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json)
