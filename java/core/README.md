# copilot-agent-core

Framework-neutral agent API and engine shared by the Java integrations in this
repository. Applications do not depend on it directly; it arrives transitively through
the [Spring Boot starter](../spring-boot/) or the [Quarkus extension](../quarkus/).

- `io.github.brunoborges.copilot.agent`: the public API (`@CopilotAgent`, `CopilotAgents`,
  `AgentConversation`, hooks, telemetry, and related types).
- `io.github.brunoborges.copilot.agent.internal`: the shared engine (agent definitions,
  proxies, invocation, governance, tool/MCP/provider binding). Frameworks plug in through
  `BindingContext` and `BeanLookup`. This package is not a supported API.

The module has no Maven wrapper of its own; both integration builds include it as a
reactor module (`<module>../core</module>`):

```
cd spring-boot && ./mvnw verify   # or: cd quarkus && ./mvnw verify
```
