---
title: Agent interfaces
---

<a id="agent-interfaces"></a>

# Agent interfaces

**In this chapter**

- [CopilotAgent](#copilotagent)
- [ConversationId and UserMessage](#conversationid-and-usermessage)
- [EnableCopilotAgents](#enablecopilotagents)

The application-facing annotations are in
`io.github.brunoborges.copilot.agent`.

<a id="copilotagent"></a>

## CopilotAgent

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

<a id="conversationid-and-usermessage"></a>

## ConversationId and UserMessage

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

<a id="enablecopilotagents"></a>

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
