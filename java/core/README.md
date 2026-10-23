# copilot-agent-core

Framework-neutral agent API and engine shared by the Java integrations in this
repository. Applications do not depend on it directly; it arrives transitively through
the [Spring Boot starter](../spring-boot/), the [Quarkus extension](../quarkus/), or the
[Micronaut integration](../micronaut/).

- `io.github.brunoborges.copilot.agent`: the public API (`@CopilotAgent`, `CopilotAgents`,
  `AgentConversation`, hooks, telemetry, and related types), plus `CopilotSessionStore` for
  listing, reading, and deleting the agents' persisted sessions.
- `io.github.brunoborges.copilot.agent.internal`: the shared engine (agent definitions,
  proxies, invocation, governance, tool/MCP/provider binding). Frameworks plug in through
  `BindingContext` and `BeanLookup`. `DefaultCopilotSessionStore` and
  `CopilotSessionCleaner` implement the session store and the opt-in age-based cleanup
  that each framework schedules. This package is not a supported API.

The module has no Maven wrapper of its own; each integration build includes it as a
reactor module (`<module>../core</module>`):

```
cd spring-boot && ./mvnw verify   # or: cd quarkus (or micronaut) && ./mvnw verify
```
