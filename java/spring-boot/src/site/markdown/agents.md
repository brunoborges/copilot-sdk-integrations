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
- [EnableCopilotAgents](#enablecopilotagents)

The application-facing annotations are in
`io.github.brunoborges.copilot.agent`.

<a id="copilotagent"></a>

## CopilotAgent

`@CopilotAgent` is a runtime annotation on an interface. Explicit-ID interfaces
produce singleton routing proxies. ID-less interfaces register validated agent
definitions, not singleton business proxies; use `CopilotAgents` to obtain a
conversation-bound instance. Both forms share the same declarative settings.

Attribute values fall into three kinds, and the name tells you which:

- **Bean references** end in `Ref` or `Refs` (for example `toolRefs` or
  `permissionHandlerRef`). Each value must be the name of a Spring bean of the
  documented type. Startup fails and names the attribute when no such bean
  exists or the bean has the wrong type.
- **Named entries** (`mcpServers`, `provider`) are looked up first under
  `copilot.mcp.servers.<name>` or `copilot.providers.<name>`, then as a native
  SDK bean with the same name. Defining a name in both places is an error.
- **Literals** (`name`, `instructions`, `model`, `availableTools`, and so on)
  are plain values passed to the SDK.

| Attribute | Type | Default | Contract |
| --- | --- | --- | --- |
| `name` | `String` | `""` | Empty derives a JavaBeans-decapitalized interface simple name, such as `SpringAdvisor` to `springAdvisor`. Explicit names must match `[A-Za-z][A-Za-z0-9._-]*` and be unique. |
| `instructions` | `String` | `""` | Inline system-message content. Mutually exclusive with `instructionsResource` and `systemMessageRef`. |
| `instructionsResource` | `String` | `""` | UTF-8 Spring resource location. Missing or blank content fails initialization. |
| `instructionsMode` | SDK `SystemMessageMode` | `APPEND` | Inline/resource instructions support `APPEND` or `REPLACE`. Use a `systemMessageRef` bean for `CUSTOMIZE`. |
| `systemMessageRef` | `String` | `""` | Bean name of a `SystemMessageConfig` bean. The bean owns the mode; do not combine it with instruction content/resource attributes or a non-default `instructionsMode`. |
| `model` | `String` | `""` | Optional SDK model. Empty leaves selection to the runtime. A per-agent property overrides this value. |
| `reasoningEffort` | `String` | `""` | Optional model-specific reasoning effort. A per-agent property overrides this value. |
| `permissionHandlerRef` | `String` | `""` | Bean name of an SDK `PermissionHandler` bean. Empty selects the integration's rejection handler (or a policy from `copilot.agents.<name>.permissions.*`), not an arbitrary handler found by type. See [`CopilotPermissionPolicies`](governance.html#ready-made-permission-policies). |
| `toolRefs` | `String[]` | `{}` | Bean names of individual `ToolDefinition` objects or SDK-annotated Spring services. These are bean names, not tool names; filter tools by name with `availableTools`. |
| `mcpServers` | `String[]` | `{}` | Names from `copilot.mcp.servers` or native SDK `McpServerConfig` beans. Per-agent properties replace this selection. See [MCP servers](mcp.html). |
| `provider` | `String` | `""` | Name from `copilot.providers` or a native SDK `ProviderConfig` bean, used as a BYOK model provider. Requires a model. Per-agent properties replace this selection. See [BYOK providers](byok.html). |
| `listenerRefs` | `String[]` | `{}` | Bean names of `CopilotAgentListener` beans, invoked in listed order for each attached session's events. |
| `customizerRefs` | `String[]` | `{}` | Bean names of `CopilotAgentCustomizer` beans, applied in listed order. |
| `availableTools` | `String[]` | `{}` | SDK tool allow-list across built-in, custom, and MCP tools; must include `custom:`/`mcp:` entries when the agent declares `toolRefs`/`mcpServers`. See [Tool governance](governance.html). |
| `excludedTools` | `String[]` | `{}` | SDK tool deny-list. Mutually exclusive with `availableTools`. |
| `excludedBuiltInAgents` | `String[]` | `{}` | Runtime built-in sub-agents to hide. |
| `builtInTools` | `boolean` | `true` | `false` hides every built-in tool (`builtin:*`). Cannot be combined with `availableTools`. |
| `workingDirectory` | `String` | `""` | Session working directory for runtime tool operations. Empty uses the runtime default. |
| `additionalDirectories` | `String[]` | `{}` | Extra directories the session may access. |
| `hookRefs` | `String[]` | `{}` | Bean names of `CopilotAgentHooks` beans, run after global and targeted hooks in listed order. See [hooks](governance.html#session-hooks-as-spring-beans). |

Annotation string values support Spring property placeholders. Whitespace-only
values are invalid; empty strings on optional attributes mean unset. Instruction
resource contents are loaded verbatim, not evaluated as a template.

The name is the Spring/configuration identity. It does not select an SDK custom
agent file or change the CLI's agent profile automatically. References and
instruction resources are resolved at agent initialization, not refreshed for
every invocation. Treat configuration beans as immutable after initialization.

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
| Routed | One `@ConversationId String` plus the prompt parameters, in any order. | Inject the singleton routing bean. |
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

- `value` and `resource` are mutually exclusive. Resources are UTF-8 Spring
  resource locations read once at initialization; blank or missing templates
  fail startup.
- Spring property placeholders (`${...}`) are resolved at initialization in the
  inline template and in the resource location, like other annotation values.
  Resource contents are used verbatim.
- `{{name}}` (surrounding whitespace allowed) is replaced by the `@P("name")`
  argument rendered with `String.valueOf`. Names match
  `[A-Za-z_][A-Za-z0-9_]*`; any other `{{...}}` text is kept literally.
  Rendering is a single pass with no escaping, expressions, or conditionals, so
  argument text containing `{{...}}` is not expanded again.
- `@P` without a value uses the Java parameter name, which requires compiling
  with `-parameters` (the Spring Boot parent enables it).
- Initialization fails when a placeholder has no matching `@P` parameter, a
  `@P` parameter is not referenced, `@P` names repeat, `@P` is used without a
  method-level template, or a template is combined with a `@UserMessage`
  parameter.
- Null `@P` arguments fail the call with `IllegalArgumentException` (a failed
  future for asynchronous methods, thrown directly for synchronous methods).

<a id="return-types"></a>

## Return types

| Return type | Requirement | Result |
| --- | --- | --- |
| `CompletableFuture<String>` | Basic starter | Final assistant text; no assistant text is an error. |
| `CompletableFuture<T>` | Basic starter | Structured result deserialized into `T` (see below). |
| `String` or `T` (synchronous) | Basic starter | Blocks the caller for at most the agent's `response-timeout`. |
| `Flux<CopilotAgentEvent>` | Reactor module | Native events, followed by completion or error after the SDK operation and cleanup. |
| Another type | A matching application return-type adapter | Defined by that adapter; unsupported types fail initialization. |

**Structured results.** For any `T` other than `String`, the starter derives a
strict JSON Schema from `T` at initialization and sends it as the SDK's
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
abstract types, and recursive types fail initialization. Replies that are not
valid JSON for `T`, or are JSON `null`, fail the call with
`IllegalStateException`. Schema enforcement is provider-dependent; Jackson
deserialization is not full schema validation. Structured output is an
experimental SDK capability.

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
while its callback is blocked. Prefer the `CompletableFuture` forms on
event-loop or request threads that must not block.

Raw, wildcard, or unresolved generic variants are not automatically supported
by the built-in adapters. Default interface methods execute locally. Ordinary
proxy `equals`, `hashCode`, and `toString` do not contact the SDK.

<a id="enablecopilotagents"></a>

## Message source

Each prompt can carry a provenance label: `user`, `system` (an automated caller such as a
scheduler), or `agent-<id>` (another agent). For each call, the first match wins:

1. A `CopilotMessageSources.runAs(...)` / `callAs(...)` scope on the calling thread. Inside a tool
   handler, only a scope the handler opens itself counts; the outer caller's scope is suspended.
2. `agent-<name>` when an agent's tool handler calls another agent on the handler's thread.
3. The `copilot.agents.<name>.message-source` property (an empty value clears the annotation value).
4. `@CopilotAgent(messageSource = "system")` (placeholders are resolved).
5. Nothing: the runtime applies its default, `user`.

```java
CopilotMessageSources.runAs(MessageSource.SYSTEM, () -> digest.summarize(report));
```

Agent ids must match `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`; invalid values fail at startup (or when a
scope opens). Automatic inference never produces `user`.

> The source is caller-supplied provenance, not an authenticated identity: never authorize from it.
> It can still influence runtime behavior (auto-approval classification, queueing, steering), and
> non-user sources persist in session history, so validate identifiers that cross trust boundaries
> and never put secrets in them. `system` labels an automated caller; it does not create a
> system-role message.

## EnableCopilotAgents

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

---

**Previous:** [SDK client](client.html)

**Next:** [Bound instances](instances.html)
