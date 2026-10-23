---
title: Agent interfaces
---

<a id="agent-interfaces"></a>

# Agent interfaces

**In this chapter**

- [CopilotAgent](#copilotagent)
- [ConversationId and UserMessage](#conversationid-and-usermessage)
- [Prompt templates](#prompt-templates)
- [Return types](#return-types)
- [Agent discovery](#agent-discovery)

The application-facing annotations are in
`io.github.brunoborges.copilot.agent`. They are the same types as in the Spring
Boot starter, so agent interfaces move between the two frameworks unchanged.

<a id="copilotagent"></a>

## CopilotAgent

`@CopilotAgent` is a runtime annotation on an interface. Explicit-ID interfaces
(every abstract method has a `@ConversationId` parameter) become `@Singleton`
synthetic CDI beans that route each call by its key; inject them with
`@Inject`. ID-less interfaces register validated agent definitions, not beans;
use `CopilotAgents` to obtain a conversation-bound instance. Both forms share
the same declarative settings.

Attribute values fall into three kinds, and the name tells you which:

- **Bean references** end in `Ref` or `Refs` (for example `toolRefs` or
  `permissionHandlerRef`). Each value must be the `@Named` name of a CDI bean of
  the documented type. CDI beans have no implicit name, so give every
  referenced bean an explicit `@Named("...")` (on the class or on the producer
  method). Startup fails and names the attribute when no such bean exists, the
  bean has the wrong type, or several beans share the name.
- **Named entries** (`mcpServers`, `provider`) are looked up first under
  `quarkus.copilot.mcp.servers.<name>` or `quarkus.copilot.providers.<name>`,
  then as a `@Named` native SDK bean with the same name. Defining a name in both
  places is an error.
- **Literals** (`name`, `instructions`, `model`, `availableTools`, and so on)
  are plain values passed to the SDK.

| Attribute | Type | Default | Contract |
| --- | --- | --- | --- |
| `name` | `String` | `""` | Empty derives a JavaBeans-decapitalized interface simple name, such as `QuarkusAdvisor` to `quarkusAdvisor`. Explicit names must match `[A-Za-z][A-Za-z0-9._-]*`. The name is the `quarkus.copilot.agents.<name>` configuration key. |
| `instructions` | `String` | `""` | Inline system-message content. Mutually exclusive with `instructionsResource` and `systemMessageRef`. |
| `instructionsResource` | `String` | `""` | UTF-8 class-path resource, optionally prefixed with `classpath:` (a leading `/` is ignored), or a `file:` path. Missing or blank content fails startup. |
| `instructionsMode` | SDK `SystemMessageMode` | `APPEND` | Inline/resource instructions support `APPEND` or `REPLACE`. Use a `systemMessageRef` bean for `CUSTOMIZE`. |
| `systemMessageRef` | `String` | `""` | Bean name of a `SystemMessageConfig` bean. The bean owns the mode; do not combine it with instruction content/resource attributes or a non-default `instructionsMode`. |
| `model` | `String` | `""` | Optional SDK model. Empty leaves selection to the runtime. A per-agent property overrides this value. |
| `reasoningEffort` | `String` | `""` | Optional model-specific reasoning effort. A per-agent property overrides this value. |
| `permissionHandlerRef` | `String` | `""` | Bean name of an SDK `PermissionHandler` bean. Empty selects the integration's rejection handler (or a policy from `quarkus.copilot.agents.<name>.permissions.*`), not an arbitrary handler found by type. See [`CopilotPermissionPolicies`](governance.html#ready-made-permission-policies). |
| `toolRefs` | `String[]` | `{}` | Bean names of individual `ToolDefinition` objects or SDK-annotated CDI beans. These are bean names, not tool names; filter tools by name with `availableTools`. See [CDI tool beans](tools.html). |
| `mcpServers` | `String[]` | `{}` | Names from `quarkus.copilot.mcp.servers` or native SDK `McpServerConfig` beans. Per-agent properties replace this selection. See [MCP servers](mcp.html). |
| `provider` | `String` | `""` | Name from `quarkus.copilot.providers` or a native SDK `ProviderConfig` bean, used as a BYOK model provider. Requires a model. Per-agent properties replace this selection. See [BYOK providers](byok.html). |
| `listenerRefs` | `String[]` | `{}` | Bean names of `CopilotAgentListener` beans, invoked in listed order for each attached session's events. |
| `customizerRefs` | `String[]` | `{}` | Bean names of `CopilotAgentCustomizer` beans, applied in listed order. |
| `availableTools` | `String[]` | `{}` | SDK tool allow-list across built-in, custom, and MCP tools; must include `custom:`/`mcp:` entries when the agent declares `toolRefs`/`mcpServers`. See [Tool governance](governance.html). |
| `excludedTools` | `String[]` | `{}` | SDK tool deny-list. Mutually exclusive with `availableTools`. |
| `excludedBuiltInAgents` | `String[]` | `{}` | Runtime built-in sub-agents to hide. |
| `builtInTools` | `boolean` | `true` | `false` hides every built-in tool (`builtin:*`). Cannot be combined with `availableTools`. |
| `toolSearch` | `String` | `""` | `"true"` or `"false"` toggles runtime tool search, which defers MCP and external tool definitions when the agent has many tools; empty keeps the runtime default. |
| `toolSearchDeferThreshold` | `int` | `0` | Total tool count at which tool search defers tools; `0` keeps the runtime default (30). |
| `workingDirectory` | `String` | `""` | Session working directory for runtime tool operations. Empty uses the runtime default. |
| `additionalDirectories` | `String[]` | `{}` | Extra directories the session may access. |
| `hookRefs` | `String[]` | `{}` | Bean names of `CopilotAgentHooks` beans, run after global and targeted hooks in listed order. See [hooks](governance.html). |
| `tokenResolverRef` | `String` | `""` | Bean name of a `CopilotTokenResolver` supplying the per-invocation GitHub token. Empty uses the single `CopilotTokenResolver` bean if exactly one exists (several fail startup), otherwise the client's ambient authentication. See [Per-user authentication](authentication.html). |

Annotation string values support MicroProfile Config placeholders, `${key}` and
`${key:default}`; an unresolvable placeholder without a default fails startup.
Whitespace-only values are invalid; empty strings on optional attributes mean
unset. Instruction resource contents are loaded verbatim, not evaluated as a
template.

The name is the CDI/configuration identity. It does not select an SDK custom
agent file or change the CLI's agent profile automatically. Quarkus does not
reject two interfaces declaring the same name (their SDK sessions stay
separate), but they would share the same `quarkus.copilot.agents.<name>.*`
overrides, so keep names unique. References and instruction resources are
resolved when the agent is initialized during application startup, not
refreshed for every invocation. Treat configuration beans as immutable after
startup.

<a id="conversationid-and-usermessage"></a>

## ConversationId and UserMessage

| Annotation | Meaning |
| --- | --- |
| `@ConversationId` | Application-defined conversation key on a `String` parameter. Not the SDK session ID and not an authorization credential. |
| `@UserMessage` (parameter) | The `String` argument is the prompt, passed verbatim to the SDK. |
| `@UserMessage("...")` / `@UserMessage(resource = "...")` (method) | Prompt template rendered from `@P` parameters. See [Prompt templates](#prompt-templates). |
| `@P("name")` | Binds a parameter to the `{{name}}` placeholder of the method's template. |

Choose one conversation form for an entire interface:

| Form | Parameters | Consumption |
| --- | --- | --- |
| Routed | One `@ConversationId String` plus the prompt parameters, in any order. | `@Inject` the singleton routing bean. |
| Bound | Prompt parameters only, no `@ConversationId`. | Obtain a handle through `CopilotAgents.create` or `resume`. |

Each method declares its prompt in exactly one way: one `@UserMessage String`
parameter, or a method-level `@UserMessage` template with zero or more `@P`
parameters. Every parameter carries exactly one of `@ConversationId`,
`@UserMessage`, or `@P`.

Do not mix the two conversation forms in one interface. Null/blank prompts,
null template arguments, or invalid explicit conversation keys fail with
`IllegalArgumentException` without SDK work. Omitting `@ConversationId` does
not opt into a hidden global conversation.

<a id="prompt-templates"></a>

## Prompt templates

```java
@CopilotAgent(name = "summarizer")
public interface Summarizer {

    @UserMessage("Summarize {{doc}} for {{audience}} in ${summaries.max-words:100} words.")
    CompletableFuture<String> summarize(@ConversationId String id,
            @P("doc") String document, @P("audience") String audience);

    @UserMessage(resource = "classpath:prompts/release-notes.txt")
    CompletableFuture<String> releaseNotes(@ConversationId String id, @P("changes") List<String> changes);
}
```

- `value` and `resource` are mutually exclusive. Resources are UTF-8 class-path
  resources (optionally prefixed with `classpath:`) or `file:` paths, read once
  at startup; blank or missing templates fail startup.
- Configuration placeholders (`${...}`) are resolved at startup in the inline
  template and in the resource location, like other annotation values.
  Resource contents are used verbatim.
- `{{name}}` (surrounding whitespace allowed) is replaced by the `@P("name")`
  argument rendered with `String.valueOf`. Names match
  `[A-Za-z_][A-Za-z0-9_]*`; any other `{{...}}` text is kept literally.
  Rendering is a single pass with no escaping, expressions, or conditionals, so
  argument text containing `{{...}}` is not expanded again.
- `@P` without a value uses the Java parameter name, which requires compiling
  with `-parameters` (`<parameters>true</parameters>` on
  `maven-compiler-plugin`).
- Startup fails when a placeholder has no matching `@P` parameter, a `@P`
  parameter is not referenced, `@P` names repeat, `@P` is used without a
  method-level template, or a template is combined with a `@UserMessage`
  parameter.
- Null `@P` arguments fail the call with `IllegalArgumentException` (a failed
  future for asynchronous methods, thrown directly for synchronous methods).

<a id="return-types"></a>

## Return types

| Return type | Requirement | Result |
| --- | --- | --- |
| `CompletableFuture<String>` | None | Final assistant text; no assistant text is an error. |
| `CompletableFuture<T>` | None | Structured result deserialized into `T` (see below). |
| `String` or `T` (synchronous) | None | Blocks the caller for at most the agent's `response-timeout`. |
| `Multi<CopilotAgentEvent>` | Mutiny on the class path | Native events, followed by completion or failure after the SDK operation and cleanup. See [Mutiny streaming](streaming.html). |
| Another type | Exactly one matching application `CopilotAgentReturnTypeAdapter` bean | Defined by that adapter; unsupported types fail startup, and several matching adapters fail startup too. |

Return a `CompletableFuture` (a `CompletionStage`) straight from a Quarkus REST
resource method to answer asynchronously without blocking the I/O thread.

**Structured results.** For any `T` other than `String`, the extension derives a
strict JSON Schema from `T` at startup and sends it as the SDK's
provider-native response schema (`MessageOptions.setResponseSchema`), then
reads the final assistant JSON with Jackson. Supported types:

- records and concrete JavaBeans (properties discovered by Jackson, honoring
  annotations such as `@JsonProperty`); every property is required and objects
  are closed (`additionalProperties: false`);
- enums (constants as Jackson serializes them), `String`, `boolean`, integral
  and floating-point primitives and wrappers, `BigInteger`, `BigDecimal`,
  `UUID`, and common `java.time` types;
- arrays, `List`, `Set`, and `Collection` of supported types.

Non-object results such as `List<Verdict>`, enums, or `Boolean` are wrapped in a
`{"value": ...}` object because strict schemas require an object root; the
wrapper is removed transparently. Maps, `Optional`, `Object`, wildcards,
abstract types, and recursive types fail startup. Replies that are not valid
JSON for `T`, or are JSON `null`, fail the call with `IllegalStateException`.
Schema enforcement is provider-dependent; Jackson deserialization is not full
schema validation. Structured output is an experimental SDK capability.

```java
public record Verdict(Sentiment sentiment, double confidence, List<String> reasons) {}

@CopilotAgent(name = "classifier")
public interface Classifier {
    CompletableFuture<Verdict> classify(@ConversationId String id, @UserMessage String review);

    @UserMessage("Is this message spam? {{text}}")
    boolean spam(@ConversationId String id, @P("text") String text);
}
```

**Synchronous methods.** A non-future return type blocks the calling thread
until the result is available or the agent's `response-timeout` elapses
(measured from the call, including admission and queueing); on timeout the
invocation is cancelled. Unchecked failures are rethrown as-is. Checked
failures such as `TimeoutException` are thrown directly when the method
declares them and are otherwise wrapped in `CompletionException`. Never call a
synchronous agent method from SDK callback threads (listeners, tool handlers,
permission handlers, or event consumers): the SDK cannot deliver the reply
while its callback is blocked. Do not call synchronous agent methods on a
Vert.x event-loop thread either; in Quarkus REST, use them only from methods
that run on a worker or virtual thread (for example `@Blocking` or
`@RunOnVirtualThread`), or prefer the `CompletableFuture` and `Multi` forms.

Raw, wildcard, or unresolved generic variants are not automatically supported
by the built-in adapters. Default interface methods execute locally. Ordinary
proxy `equals`, `hashCode`, and `toString` do not contact the SDK.

<a id="agent-discovery"></a>

## Agent discovery

There is no `@EnableCopilotAgents` annotation and no package scanning
configuration. At build time, the deployment processor finds every
`@CopilotAgent` in the Quarkus combined Jandex index: the application's own
classes plus any dependency that Quarkus indexes (one that contains a Jandex
index or `beans.xml`, or is listed in `quarkus.index-dependency.*`). To use agent
interfaces from a library, make sure that library is indexed.

For each discovered interface the build:

- fails with `@CopilotAgent must annotate an interface` when the annotation is
  on a class;
- registers a `@Singleton` synthetic bean when every abstract method (including
  inherited ones) has a `@ConversationId` parameter, and otherwise records an
  ID-less definition for `CopilotAgents`;
- registers the interface as a dynamic proxy for native images.

At application startup every discovered agent is initialized and validated, so
a missing tool bean, resource, or unknown MCP server fails the application
instead of the first request. Use `@CopilotAgent` directly on interfaces; it is
not an ordinary CDI stereotype or a supported composed-annotation mechanism.

---

**Previous:** [Coming from Spring Boot](spring-boot.html)

**Next:** [Bound instances](instances.html)
