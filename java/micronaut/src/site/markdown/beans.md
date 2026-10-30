---
title: Beans, tools, and hooks
---

<a id="beans"></a>

# Beans, tools, and hooks

<a id="referencing-beans"></a>

## Referencing beans

Attributes ending in `Ref`/`Refs` (`toolRefs`, `systemMessageRef`,
`permissionHandlerRef`, `listenerRefs`, `customizerRefs`, `hookRefs`,
`tokenResolverRef`) name beans. Give each referenced bean an explicit
`@Named("...")`.

<a id="tools"></a>

## Micronaut beans as tools

Annotate bean methods with the SDK's `@CopilotTool` and reference the bean from
`toolRefs`:

```java
@Singleton
@Named("referenceTools")
public class ReferenceTools {

    @CopilotTool(value = "Look up a definition", name = "describe_term")
    public String describeTerm(@CopilotToolParam("Term") String term) { ... }
}
```

<a id="ordering-and-overrides"></a>

## Ordering and overrides

Beans looked up by type (hooks, return-type adapters, telemetry decorators) are
ordered with Micronaut's `@Order`/`Ordered`, lower values first.

The integration's default beans (`CopilotClient`, `CopilotClientOptions`,
`CopilotAgents`, telemetry) are `@Secondary`, so an application bean of the same
type wins; use `@Replaces` to remove a default entirely.

<a id="permissions"></a>

## Permissions

Permissions are denied unless you supply an explicit `PermissionHandler` bean or a
permission policy (`micronaut.copilot.agents.<name>.permissions.*`). The example
application's handler rejects every request:

```java
@Singleton
@Named("denyPermissions")
public class DenyPermissions implements PermissionHandler {

    @Override
    public CompletableFuture<PermissionRequestResult> handle(PermissionRequest request, PermissionInvocation invocation) {
        return CompletableFuture.completedFuture(
                PermissionRequestResult.reject("This example denies all permission requests."));
    }
}
```

Tool allow/deny lists per agent are set with `excluded-tools` and related
properties in [Configuration](configuration.html).

<a id="hooks"></a>

## Session hooks

Hook beans apply to every agent with `@GlobalCopilotAgentHooks`, or to selected
agents with `@CopilotAgentHooksFor`.

`CopilotErrorRecoveryHooks` (from copilot-core) is an opt-in hook based on the
Copilot SDK cookbook's
[error-recovery recipe](https://github.com/github/awesome-copilot/blob/main/cookbook/copilot-sdk/python/error-recovery-hooks.md).
When a tool fails it adds a note telling the model to keep going, and it stops
after `maxConsecutiveFailures` consecutive failures of the same tool:

```java
@Factory
class RecoveryFactory {
    @Singleton
    @GlobalCopilotAgentHooks
    CopilotErrorRecoveryHooks errorRecovery() {
        return CopilotErrorRecoveryHooks.builder().maxConsecutiveFailures(3).build();
    }
}
```

The Java SDK has no error-occurred hook, so transient model or runtime errors are not retried.

<a id="authentication"></a>

## Per-user authentication

Supply a `CopilotTokenResolver` bean, or enable
`micronaut.copilot.security.identity-token-resolver.enabled=true` to authenticate
agents as the current Micronaut Security `Authentication`. The token is read from
the attribute named by `micronaut.copilot.security.identity.token-attribute`
(default `accessToken`).

Micronaut's `PropagatedContext` (for example the current HTTP request) is
propagated into tools, permission handlers, hooks, and listeners.
