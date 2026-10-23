---
title: Per-user authentication
---

<a id="per-user-authentication"></a>

# Per-user authentication

**In this chapter**

- [Token resolvers](#token-resolvers)
- [Session isolation](#session-isolation)
- [Global and per-agent selection](#global-and-per-agent-selection)
- [Quarkus Security identity](#quarkus-security-identity)
- [Caller context in tools and handlers](#caller-context-in-tools-and-handlers)
- [Security notes](#security-notes)

By default every session uses the ambient credentials of the shared
`CopilotClient`: the signed-in CLI user, `GH_TOKEN`/`GITHUB_TOKEN`, or the
client's `gitHubToken` option. A multi-user application can instead run each
turn as the end user. The SDK accepts a GitHub token per session
(`SessionConfig`/`ResumeSessionConfig.setGitHubToken`). The extension fills it
from a `CopilotTokenResolver`.

<a id="token-resolvers"></a>

## Token resolvers

`com.microsoft.copilot.integrations.core.CopilotTokenResolver` is a functional
interface. Implement it as a CDI bean:

```java
@ApplicationScoped
public class CopilotTokens implements CopilotTokenResolver {

    @Inject
    UserTokenStore store;

    @Inject
    SecurityIdentity identity;

    @Override
    public String resolveToken(CopilotAgentContext context) {
        return store.gitHubTokenFor(identity.getPrincipal().getName());   // null = ambient auth
    }

    @Override
    public String partitionKey(String agentName, String conversationId) {
        return identity.getPrincipal().getName();                        // isolates sessions per user
    }
}
```

| Rule | Behavior |
| --- | --- |
| When it runs | Once per invocation, **on the calling thread**, before admission returns. The CDI request context, request-scoped beans, and the current `SecurityIdentity` are available. |
| Argument | `CopilotAgentContext` with the agent name, conversation key, and session ID. |
| `null` result | Ambient client authentication for that turn. |
| Exception | The returned future fails (or the `Multi` fails); no SDK work is started and no capacity is consumed. |
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

The built-in [identity resolver](#quarkus-security-identity) partitions by
principal name. Under `ambient-fallback`, unauthenticated callers share the
unpartitioned space. Changing the partition key (for example renaming users)
starts new sessions.

For `Multi<CopilotAgentEvent>` methods the resolver runs when the stream is
subscribed. Subscribe while the caller's request context is active, as Quarkus
REST does when a resource method returns the `Multi`.

<a id="global-and-per-agent-selection"></a>

## Global and per-agent selection

| Selection | Configuration |
| --- | --- |
| Global | Exactly one `CopilotTokenResolver` bean applies to every agent. |
| Per agent | `@CopilotAgent(tokenResolverRef = "beanName")` selects a resolver bean with that `@Named` value and overrides the global one. |
| None | No resolver bean: ambient authentication. |

A missing named bean fails initialization. CDI has no `@Primary`: with several
resolver beans, every agent that does not set `tokenResolverRef` fails
initialization, so select one per agent. The built-in identity resolver, when
enabled, counts as a resolver bean; it has no `@Named` value, so it can only be
the global resolver.

```java
@CopilotAgent(name = "reviewer", tokenResolverRef = "serviceAccountTokens")
interface Reviewer { /* ... */ }
```

<a id="quarkus-security-identity"></a>

## Quarkus Security identity

The extension includes
`com.microsoft.copilot.integrations.quarkus.runtime.security.IdentityCopilotTokenResolver`,
which uses the bearer access token of the current Quarkus `SecurityIdentity`.
It replaces the Spring starter's OAuth2 client resolver. Enable it with a
build-time switch; the build fails if no Quarkus Security extension is present:

```ini
quarkus.copilot.security.identity-token-resolver.enabled=true

# For example, GitHub sign-in through quarkus-oidc
quarkus.oidc.provider=github
quarkus.oidc.client-id=${GITHUB_CLIENT_ID}
quarkus.oidc.credentials.secret=${GITHUB_CLIENT_SECRET}
```

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `quarkus.copilot.security.identity-token-resolver.enabled` | Boolean (build time) | `false` | Register the identity resolver as the global `CopilotTokenResolver`. Requires a Quarkus Security extension. |
| `quarkus.copilot.security.identity.ambient-fallback` | Boolean | `false` | Use ambient client authentication instead of failing when there is no authenticated identity or bearer token. |

The resolver reads the `SecurityIdentity` on the invoking thread. Anonymous
identities and an inactive request context count as unauthenticated. The token
is the first credential of type `TokenCredential` whose type is `bearer`
(for example the access token obtained by `quarkus-oidc`); an identity without
one fails the invocation unless `ambient-fallback` is set. Sessions are
partitioned by `identity:` plus the principal name. The token must be one the
Copilot runtime accepts, such as a GitHub App user-to-server token for a user
with Copilot access. To customize the behavior, leave the switch off and
provide your own `CopilotTokenResolver` bean.

<a id="caller-context-in-tools-and-handlers"></a>

## Caller context in tools and handlers

SDK work runs on virtual threads, and tools, permission handlers, hooks, and
native listeners run on SDK threads. The extension captures the caller's active
CDI request context with the token and reactivates it around every tool call,
permission request, hook callback, and listener event. It deactivates it
afterwards.

| Value | Propagated |
| --- | --- |
| CDI request context (request-scoped beans, including `SecurityIdentity`) | Yes, while the original request context is still valid. |
| Application-scoped and singleton beans | Not needed; available everywhere. |
| JBoss Logging / SLF4J MDC | No. |
| Your own thread locals | No. |

So a `@CopilotTool` method on a CDI bean can `@Inject SecurityIdentity` and read
the caller, and because tools are invoked through the CDI client proxy,
interceptors such as security annotations apply. If the request context is not
active when the agent is invoked, or has already been destroyed when a callback
runs (for example detached work after the HTTP response completed), nothing is
reactivated and request-scoped beans throw `ContextNotActiveException`. A
callback thread whose request context is already active keeps it. Resolve
identity explicitly when work outlives the request.

<a id="security-notes"></a>

## Security notes

- Tokens are never logged. `toString` output of internal state and the
  identity resolver omit them, and metrics do not tag them.
- A per-session token selects the identity used for the runtime's GitHub
  calls. Session isolation comes from the [partition key](#session-isolation),
  so return a key that identifies the authenticated caller, never a value the
  caller controls.
- Sharing conversations across users (`SHARED`) is an application
  authorization decision; the extension does not check it.
- Context propagation does not cross process boundaries such as remote MCP
  servers.

---

**Previous:** [BYOK providers](byok.html)

**Next:** [Events and customizers](events.html)
