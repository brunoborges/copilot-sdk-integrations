# Copilot for Apache Camel

Run GitHub Copilot agents as route steps and expose Camel routes to agents as tools,
permission handlers, and session event sinks. The `copilot:` component uses the
Copilot SDK for Java and the same framework-neutral agent engine as the Spring Boot
and Quarkus integrations.

**Status: available, unreleased.** Build the Maven artifacts from this repository;
they are not yet published to Maven Central.

```java
from("direct:ask")
    .to("copilot:agent:support?instructions=Answer briefly.&model=gpt-5");
```

The exchange body is the prompt. The agent's answer replaces it, so the next route
step can transform, persist, or deliver the result with ordinary Camel components.

## Four endpoint kinds

| URI | Direction | Purpose |
| --- | --- | --- |
| `copilot:agent:name` | Producer | Send a prompt to an endpoint-configured or declarative agent. |
| `copilot:tool:name` | Consumer | Register a route as a tool the agent can call. |
| `copilot:permission:name` | Consumer | Route permission decisions to application logic or human approval. |
| `copilot:events:name` | Consumer | Consume filtered session events asynchronously. |

## Read the guides

- [Getting started](getting-started.html): prerequisites, local installation, and a complete route application.
- [Agents and conversations](agents.html): prompts, structured results, declarative interfaces, and session lifecycle.
- [Routes as tools](tools.html): typed arguments, registry registration, and explicit tool access.
- [Permissions and events](permissions-events.html): deny-by-default execution, approvals, hooks, and event delivery.
- [Configuration and headers](configuration.html): component settings, endpoint options, and exchange metadata.
- [Testing and troubleshooting](testing.html): scripted offline tests, build commands, and operational guidance.

## Safe defaults

Endpoint-configured agents start without built-in CLI tools and reject permission
requests unless explicitly allowed. Tools must be selected by the agent and pass its
permission policy. Camel exception handling, redelivery, and circuit breakers remain
available around the asynchronous agent producer.

Conversation identifiers and message-source labels are application-supplied context,
not authenticated identities. Authorize requests before they reach an agent, validate
tool arguments, and restrict credentials and permissions to the workload.
