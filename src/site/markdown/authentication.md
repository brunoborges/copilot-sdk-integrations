---
title: Per-user authentication
---

<a id="per-user-authentication"></a>

# Per-user authentication

**In this chapter**

- [Token resolvers](#token-resolvers)
- [Session isolation](#session-isolation)
- [Global and per-agent selection](#global-and-per-agent-selection)
- [Spring Security OAuth2 client](#spring-security-oauth2-client)
- [Caller context in tools and handlers](#caller-context-in-tools-and-handlers)
- [Security notes](#security-notes)

By default every session uses the ambient credentials of the shared
`CopilotClient`: the signed-in CLI user, `GH_TOKEN`/`GITHUB_TOKEN`, or the
client's `gitHubToken` option. A multi-user application can instead run each
turn as the end user. The SDK accepts a GitHub token per session
(`SessionConfig`/`ResumeSessionConfig.setGitHubToken`). The integration fills it
from a `CopilotTokenResolver`.

<a id="token-resolvers"></a>

## Token resolvers

`io.github.brunoborges.copilot.agent.CopilotTokenResolver` is a functional
interface:

```java
@Bean
CopilotTokenResolver copilotTokens(UserTokenStore store) {
    return new CopilotTokenResolver() {
        @Override
        public String resolveToken(CopilotAgentContext context) {
            return store.gitHubTokenFor(currentUserId());   // null = ambient auth
        }

        @Override
        public String partitionKey(String agentName, String conversationId) {
            return currentUserId();                         // isolates sessions per user
        }
    };
}
```

| Rule | Behavior |
| --- | --- |
| When it runs | Once per invocation, **on the calling thread**, before admission returns. Request-scoped beans, `SecurityContextHolder`, and other thread-bound state are available. |
| Argument | `CopilotAgentContext` with the agent name, conversation key, and session ID. |
| `null` result | Ambient client authentication for that turn. |
| Exception | The returned future fails (or the stream errors); no SDK work is started and no capacity is consumed. |
| Application | The captured token is set when the session is created **and** when it is resumed; every turn re-supplies it. |
| Customizers | Run first. If a customizer already set `gitHubToken` or `gitHubTokenProvider`, the resolved token is not applied. |

<a id="session-isolation"></a>

### Session isolation

Agent session IDs are derived from the agent and the conversation ID. Without
more, two users presenting the same conversation ID would share, and resume,
the same session and its history. `partitionKey(agentName, conversationId)`
runs on the calling thread before `resolveToken` and returns a stable,
non-secret caller key (for example the principal name). It is hashed into the
session ID, so each caller gets their own session for the same conversation ID.
`CopilotAgentContext.sessionId()` reflects the partitioned ID.

| `partitionKey` result | Behavior |
| --- | --- |
| Non-empty string | Sessions are isolated per key. |
| `CopilotTokenResolver.SHARED` (`""`) | Deliberately shared: the unpartitioned session ID is used. |
| `null` (the default) | Allowed only when `resolveToken` returns `null`; an invocation that resolves a token without a partition key fails before any SDK work. |

The OAuth2 resolver partitions by registration ID and principal name. Under
`ambient-fallback`, unauthenticated callers share the unpartitioned space.
Changing the partition key (for example renaming users) starts new sessions.

For `Flux` methods the resolver runs when the stream is subscribed. In WebFlux
applications, enable Reactor's automatic context propagation
(`spring.reactor.context-propagation=auto`) so thread-bound values are present
at subscription time.

<a id="global-and-per-agent-selection"></a>

## Global and per-agent selection

| Selection | Configuration |
| --- | --- |
| Global | Exactly one `CopilotTokenResolver` bean (or one marked `@Primary`) applies to every agent. |
| Per agent | `@CopilotAgent(tokenResolver = "beanName")` selects a named resolver bean and overrides the global one. |
| None | No resolver bean: ambient authentication. |

A missing named bean, or several unqualified resolver beans without `@Primary`,
fails initialization. The auto-configured OAuth2 resolver counts as a resolver
bean.

```java
@CopilotAgent(name = "reviewer", tokenResolver = "serviceAccountTokens")
interface Reviewer { /* ... */ }
```

<a id="spring-security-oauth2-client"></a>

## Spring Security OAuth2 client

With `spring-security-oauth2-client` on the classpath and a registration ID
configured, an `OAuth2CopilotTokenResolver` bean
(`io.github.brunoborges.copilot.security`) uses the current user's authorized
client access token:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          github:
            client-id: ${GITHUB_CLIENT_ID}
            client-secret: ${GITHUB_CLIENT_SECRET}
copilot:
  security:
    oauth2:
      registration-id: github
```

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `copilot.security.oauth2.registration-id` | String | Unset | OAuth2 client registration whose access token becomes the session GitHub token. Enables the resolver. |
| `copilot.security.oauth2.ambient-fallback` | Boolean | `false` | Use ambient client authentication instead of failing when there is no authenticated user or authorized client. |

The resolver uses an `OAuth2AuthorizedClientManager` bean when one is available
(supporting refresh), otherwise an `OAuth2AuthorizedClientService`. Neither
present fails startup. It reads the `Authentication` from
`SecurityContextHolder`; anonymous users count as unauthenticated. Define your
own `OAuth2CopilotTokenResolver` bean to replace it. The token must be one the
Copilot runtime accepts, such as a GitHub App user-to-server token for a user
with Copilot access.

<a id="caller-context-in-tools-and-handlers"></a>

## Caller context in tools and handlers

SDK work runs on virtual threads, and tools, permission handlers, hooks, and
native listeners run on SDK threads. When `io.micrometer:context-propagation` is on
the classpath (it is with Actuator or Micrometer observations), the integration
captures the caller's registered thread-local values with the token and
restores them around every tool call, permission request, hook callback, and
listener event.
It removes them afterwards.

| Value | Propagated by |
| --- | --- |
| Spring Security `SecurityContext` | Spring Security's own `ThreadLocalAccessor` (registered automatically). |
| SLF4J `MDC` | `Slf4jThreadLocalAccessor`, used when no MDC accessor is registered globally. |
| Current Micrometer `Observation` | Micrometer's `ObservationThreadLocalAccessor`. |
| Your own thread locals | Any `ThreadLocalAccessor` registered in `ContextRegistry.getInstance()`. |

So a `@CopilotTool` method on a Spring bean can call
`SecurityContextHolder.getContext().getAuthentication()`, log with the
caller's MDC, or use method security such as `@PreAuthorize`. A caller with no
security context does not see a stale context left on a reused SDK thread.
Without context-propagation on the classpath, nothing is propagated; resolve
identity explicitly.

<a id="security-notes"></a>

## Security notes

- Tokens are never logged. `toString` output of internal state and the OAuth2
  resolver omit them, and observations do not tag them.
- A per-session token selects the identity used for the runtime's GitHub
  calls. Session isolation comes from the [partition key](#session-isolation),
  so return a key that identifies the authenticated caller, never a value the
  caller controls.
- Sharing conversations across users (`SHARED`) is an application
  authorization decision; the integration does not check it.
- Context propagation does not cross process boundaries such as remote MCP
  servers.

---

**Previous:** [BYOK providers](byok.html)

**Next:** [Events and customizers](events.html)
