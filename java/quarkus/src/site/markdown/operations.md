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
  Use environment variables or a Quarkus config source/secret store, for example
  `quarkus.copilot.providers.foundry.api-key=${FOUNDRY_API_KEY}`.
- Raw external TCP connections need a trusted network or separately secured
  tunnel; an `https://` spelling does not add TLS.
- Tool, permission, hook, and listener callbacks run on SDK threads. When the
  caller has an active CDI request context, the extension re-activates it
  there, so request-scoped beans, including the current `SecurityIdentity`, and
  interceptors such as `@RolesAllowed` on tool beans see the invoking request.
  Nothing else (MDC, OpenTelemetry context) is propagated, and calls made
  without an active request context propagate nothing; resolve trusted identity
  explicitly in permission/tool policies.
- For multi-user applications, run turns with each user's GitHub token through
  a `CopilotTokenResolver` bean or the built-in `IdentityCopilotTokenResolver`
  (`quarkus.copilot.security.identity-token-resolver.enabled=true`, which uses
  the bearer token of the current `SecurityIdentity` and partitions sessions by
  principal). See [Per-user authentication](authentication.html). Resolvers run
  on the calling thread, and tokens are never logged or rendered by `toString`.
- The `/q/copilot` endpoint is off by default and has no authorization of its
  own; protect it with HTTP permissions if you enable it. See
  [Health, info, and endpoint](management.html#security-and-privacy).
- Decide whether application event listeners may log assistant text or tool
  data. Built-in metric tags deliberately omit those payloads.

<a id="troubleshooting"></a>

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Build fails with `@CopilotAgent must annotate an interface` | Agents are interfaces; the extension generates the implementation. |
| Unsatisfied dependency for an agent interface | Agents are discovered from the Jandex index at build time: put the interface in the application or in an indexed dependency (Jandex index or `quarkus.index-dependency.*`), and verify `quarkus.copilot.enabled` is not `false`. ID-less definitions are deliberately not injectable beans. |
| ID-less interface cannot be injected | Inject `CopilotAgents`, then obtain `CopilotAgentInstance<T>` through `create` or `resume`. |
| Startup fails with `No bean named '...'` | `*Ref`/`*Refs` attributes name CDI beans. Add an explicit `@Named("...")`; CDI beans have no implicit names. `Several beans are named '...'` means the name is ambiguous. |
| Startup fails with `Expected at most one ... CopilotTokenResolver bean` | Select a resolver per agent with `tokenResolverRef`, or keep only one unqualified resolver bean. |
| Hooks or adapters run in an unexpected order | Type-based lookups are ordered by `@Priority`, **higher first** (Spring's `@Order` was lower first). |
| Unsupported method contract | Use supported return types and one consistent form per interface: routed (with a `@ConversationId` parameter) or bound (without). The prompt is either one `@UserMessage` string parameter or a method-level `@UserMessage` template whose `{{placeholders}}` exactly match its `@P` parameters. Structured results must be records, JavaBeans, enums, scalars, or collections of them. `Multi<CopilotAgentEvent>` requires Mutiny on the class path. |
| Agent overrides are ignored | Keys use the `@CopilotAgent` name: `quarkus.copilot.agents.<name>.*`. |
| Strict resume fails | Reuse the saved application key with the same interface/name/runtime store; merely allocating and closing a handle does not create history. |
| Close blocks or callback self-closing fails | Await workflows before closing, keep blocking close off SDK callbacks and the Vert.x event loop, and remember cancelled futures own their session until the abort and cleanup finish. |
| Blocking call on the event loop | Synchronous `String`/`T` agent methods and `.join()` block; call them from worker threads (for example `@Blocking` or a non-reactive resource method), or return `CompletionStage`/`Multi` from reactive endpoints. |
| Missing generated tool metadata | Run the SDK's `CopilotToolProcessor` annotation processor (`com.github:copilot-sdk-java`) in the module declaring the annotated methods, keep tool classes top-level, and retain generated classes in the artifact. |
| Local runtime missing | Set `quarkus.copilot.cli-path` or include the matching SDK runtime classifier. |
| External address rejected | Supply an explicit valid port; remove credentials, path/trailing slash, query, and fragment. Do not combine `cli-url` with `cli-path`, `cli-args`, or `working-directory`. |
| Repeated calls lose expected context | Reuse the same authorized conversation key, agent name/interface, and persisted runtime backing store. |
| Rejected invocations | Inspect concurrent/outstanding limits and long-running or serialized calls (`/q/copilot/execution` shows them); there is no automatic retry. |
| Missing metrics | Add `quarkus-micrometer` and a registry extension, check `quarkus.copilot.metrics.enabled`, and whether your own `CopilotAgentTelemetry` bean replaced the default. |
| Missing usage counters | Supply a meter registry and inspect whether the runtime emits usage fields; missing counts are not synthesized. |
| Readiness check `DOWN` in tests | Expected under the scripted test runtime, which does not answer status queries. |
| `/q/copilot` returns 404 | Enable it with the build-time `quarkus.copilot.endpoint.enabled=true` (requires `quarkus-vertx-http`), and check `quarkus.http.non-application-root-path`. |
| Stream overflows | Consume/offload deliberately, reduce producer work or adjust `quarkus.copilot.streaming.buffer-capacity`; remember downstream operators add their own buffers. |
| Cancelled request still consumes usage | Cancellation and response timeouts call `CopilotSession.abort()`, but usage already consumed is billed. Look for `WARNING` logs about failed aborts; capacity is held until abort and cleanup finish. |
| Tool sees no `SecurityIdentity` or request-scoped bean | Make the call while the request context is active on the calling thread; work started from a background thread has no request context to propagate. |
| Session uses the wrong GitHub identity | Check which `CopilotTokenResolver` applies (`@CopilotAgent(tokenResolverRef)`, then the single resolver bean) and whether it returned `null` (ambient). A customizer that sets a token or provider wins. |
| Build fails with `identity-token-resolver.enabled requires a Quarkus Security extension` | Add a security extension such as `quarkus-oidc`, or disable the property. |
| Identity resolver fails with `no authenticated user` | Authenticate the request, ensure the identity carries a bearer access token, or set `quarkus.copilot.security.identity.ambient-fallback=true` to use ambient credentials. |

<a id="scope-and-source-references"></a>

## Scope and source references

Implemented declarative contracts are limited to the APIs and keys in this
reference. There is no automatic POJO structured-response agent mapping,
declarative hook/attachment configuration layer, distributed conversation
lock, user authorization implementation, automatic permission approval, or
automatic CLI installation. Advanced SDK capabilities remain available through
native beans and customizers. Native images are untested: agent interfaces are
registered as dynamic proxies and tool metadata classes for reflection, but the
Copilot SDK itself may need additional native configuration. This guide does
not claim experimental in-process runtime setup support.

Source entry points:

- [Agent API package](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/java/quarkus/runtime/src/main/java/io/github/brunoborges/copilot/agent)
- [Runtime beans and integrations](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/java/quarkus/runtime/src/main/java/io/github/brunoborges/copilot/quarkus/runtime)
- [Runtime configuration](https://github.com/brunoborges/copilot-sdk-integrations/blob/main/java/quarkus/runtime/src/main/java/io/github/brunoborges/copilot/quarkus/runtime/config/CopilotRuntimeConfig.java)
- [Build steps and build-time configuration](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/java/quarkus/deployment/src/main/java/io/github/brunoborges/copilot/quarkus/deployment)
- [Test support](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/java/quarkus/test/src/main/java/io/github/brunoborges/copilot/quarkus/test)
- [Runnable example](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/java/quarkus/example/src/main/java/io/github/brunoborges/copilot/quarkus/example)

---

**Previous:** [Example application](examples.html)
