# copilot-agent-core

Framework-neutral agent API and engine shared by the Java integrations in this
repository. Applications do not depend on it directly; it arrives transitively through
the [Spring Boot starter](../spring-boot/), the [Quarkus extension](../quarkus/), or the
[Micronaut integration](../micronaut/).

- `io.github.brunoborges.copilot.agent`: the public API (`@CopilotAgent`, `CopilotAgents`,
  `AgentConversation`, hooks, telemetry, and related types).
- `io.github.brunoborges.copilot.agent.internal`: the shared engine (agent definitions,
  proxies, invocation, governance, tool/MCP/provider binding). Frameworks plug in through
  `BindingContext` and `BeanLookup`. This package is not a supported API.

The module has no Maven wrapper of its own; each integration build includes it as a
reactor module (`<module>../core</module>`):

```
cd spring-boot && ./mvnw verify   # or: cd quarkus (or micronaut) && ./mvnw verify
```

## Error-recovery hooks

`CopilotErrorRecoveryHooks` is an opt-in `CopilotAgentHooks` implementation based on the Copilot SDK
cookbook's [error-recovery recipe](https://github.com/github/awesome-copilot/blob/main/cookbook/copilot-sdk/python/error-recovery-hooks.md).
It sorts tool failures by phrase lists (permission denied, shell error, tool failure), adds a
"keep going" note as `additionalContext`, and stops after a configurable number of consecutive
failures of the same tool. Build it with `CopilotErrorRecoveryHooks.builder()` and register it like any
hooks bean; Spring Boot also offers `copilot.error-recovery.enabled=true`. The Java SDK has no
error-occurred hook, so transient model/runtime errors cannot be retried from Java.
