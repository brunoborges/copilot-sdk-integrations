---
title: Security and troubleshooting
---

<a id="security-and-troubleshooting"></a>

# Security and troubleshooting

**In this chapter**

- [Security and privacy](#security-and-privacy)
- [Troubleshooting](#troubleshooting)
- [Scope and source references](#scope-and-source-references)

<a id="security-and-privacy"></a>

## Security and privacy

- Scope conversation keys to the application and authorized user/tenant.
  Session IDs and their hashes are not authorization checks.
- Denying permission requests is not a runtime sandbox or proof that every
  operation will request permission. Narrow the visible tools and approve
  only what is needed with [tool governance, permission policies, and
  hooks](governance.html); avoid `CopilotPermissionPolicies.allowAll()`.
- Explicit SDK tool flags are preserved. Never assume registering tools
  implicitly approves them or that a default handler overrides every SDK flag.
- Keep tokens out of annotation literals, committed configuration, and logs.
  Use environment/secret mechanisms appropriate to the application.
- Raw external TCP connections need a trusted network or separately secured
  tunnel; an `https://` spelling does not add TLS.
- Tool, permission, and listener callbacks run on SDK threads. With Micrometer
  Context Propagation on the classpath, the caller's `SecurityContext`, MDC,
  and observation are restored there, so Spring proxy advice such as
  `@PreAuthorize` sees the invoking user. Without it, nothing is propagated;
  resolve trusted identity explicitly in permission/tool policies.
- For multi-user applications, run turns with each user's GitHub token through
  a `CopilotTokenResolver` or the Spring Security OAuth2 client adapter. See
  [Per-user authentication](authentication.html). Resolvers run on the calling
  thread, and tokens are never logged or rendered by `toString`.
- Decide whether application event listeners may log assistant text or tool
  data. Built-in metric tags deliberately omit those payloads.

<a id="troubleshooting"></a>

## Troubleshooting

| Symptom | Check |
| --- | --- |
| No routed agent bean | Put the directly annotated interface under Boot's base package or add `@EnableCopilotAgents`; verify integration is enabled. ID-less definitions are deliberately not singleton business beans. |
| ID-less interface cannot be injected | Inject `CopilotAgents`, then obtain `CopilotAgentInstance<T>` through `create` or `resume`. |
| Bean name collision | Set unique explicit agent names; do not rely on bean overriding. |
| Unsupported method contract | Use supported return types and one consistent form per interface: routed (with a `@ConversationId` parameter) or bound (without). The prompt is either one `@UserMessage` string parameter or a method-level `@UserMessage` template whose `{{placeholders}}` exactly match its `@P` parameters. Structured results must be records, JavaBeans, enums, scalars, or collections of them. Add the Reactor module for `Flux<CopilotAgentEvent>`. |
| Strict resume fails | Reuse the saved application key with the same interface/name/runtime store; merely allocating and closing a handle does not create history. |
| Close blocks or callback self-closing fails | Await workflows before closing, keep blocking close off SDK/event-loop callbacks, and remember cancelled futures own their session until the abort and cleanup finish. |
| Missing generated tool metadata | Run `CopilotToolProcessor` in the module declaring the annotated methods, and retain generated classes in the artifact. |
| JDK-proxied service cannot bind tools | Annotate its exposed interface and generate metadata for that interface; do not unwrap it to bypass advice. |
| Local runtime missing | Set an explicit CLI path/environment override or include the matching SDK runtime classifier. |
| External address rejected | Supply an explicit valid port; remove credentials, path/trailing slash, query, and fragment. |
| Repeated calls lose expected context | Reuse the same authorized conversation key, agent name/interface, and persisted runtime backing store. |
| Rejected invocations | Inspect concurrent/outstanding limits and long-running or serialized calls; there is no automatic retry. |
| Missing observations | Add required Micrometer classes/registry or Actuator, check the enable flag and custom telemetry replacement. |
| Missing usage counters | Supply a meter registry and inspect whether the runtime emits usage fields; missing counts are not synthesized. |
| Stream overflows | Consume/offload deliberately, reduce producer work or adjust buffer capacity; remember downstream operators add their own buffers. |
| Cancelled request still consumes usage | Cancellation and response timeouts call `CopilotSession.abort()`, but usage already consumed is billed. Look for `WARNING` logs about failed aborts; capacity is held until abort and cleanup finish. |
| Tool sees no authentication or MDC | Add `io.micrometer:context-propagation` (included with Actuator), make the call on the thread holding the context, and in WebFlux enable `spring.reactor.context-propagation=auto`. |
| Session uses the wrong GitHub identity | Check which `CopilotTokenResolver` applies (`@CopilotAgent(tokenResolver)`, then the single/`@Primary` bean) and whether it returned `null` (ambient). A customizer that sets a token or provider wins. |
| `copilot.security.oauth2` startup failure | Define an `OAuth2AuthorizedClientManager` or `OAuth2AuthorizedClientService` bean, and mark one resolver `@Primary` if you also declare your own. |

<a id="scope-and-source-references"></a>

## Scope and source references

Implemented declarative contracts are limited to the APIs and keys in this
reference. There is no automatic POJO structured-response agent mapping,
declarative MCP/hook/attachment configuration layer, distributed conversation
lock, user authorization implementation, automatic permission approval, or
automatic CLI installation. Advanced SDK capabilities remain available through
native beans and customizers. This guide does not claim native-image/AOT or
experimental in-process runtime setup support.

Source entry points:

- [Agent API package](https://github.com/brunoborges/copilot-spring-boot/tree/main/copilot-spring-boot-autoconfigure/src/main/java/io/github/brunoborges/copilot/agent)
- [Client and agent property classes](https://github.com/brunoborges/copilot-spring-boot/tree/main/copilot-spring-boot-autoconfigure/src/main/java/io/github/brunoborges/copilot/autoconfigure)
- [Reactor API and configuration](https://github.com/brunoborges/copilot-spring-boot/tree/main/copilot-spring-boot-reactor/src/main/java/io/github/brunoborges/copilot/reactor)
- [Runnable examples](https://github.com/brunoborges/copilot-spring-boot/tree/main/copilot-spring-boot-example/src/main/java/io/github/brunoborges/copilot/example)
- [Additional configuration metadata](https://github.com/brunoborges/copilot-spring-boot/blob/main/copilot-spring-boot-autoconfigure/src/main/resources/META-INF/additional-spring-configuration-metadata.json)

---

**Previous:** [Example application](examples.html)
