---
title: Getting started
---

<a id="getting-started"></a>

# Getting started

**In this chapter**

- [Modules and installation](#modules-and-installation)
- [Quick start](#quick-start)

<a id="modules-and-installation"></a>

## Modules and installation

| Artifact | Role |
| --- | --- |
| `copilot-spring-boot-parent` | Maven parent and reactor. |
| `copilot-spring-boot-site-skin` | Internal documentation layout; not an application dependency. |
| `copilot-spring-boot-autoconfigure` | Client configuration, agent API, discovery, execution, tool binding, and optional observations. Its SDK dependency is optional. |
| `copilot-spring-boot-starter` | Dependency-only starter supplying the SDK, auto-configuration, and Spring Boot starter. |
| `copilot-spring-boot-reactor` | Optional Reactor return-type adapter; includes the basic starter and Reactor, not a web framework. |
| `copilot-spring-boot-example` | Executable non-web example of raw sessions, typed agents, tools, observations, and streaming. |

No integration artifacts have been published. Install the reactor locally before
using its snapshot dependencies in another application:

```sh
./mvnw --batch-mode --no-transfer-progress install
```

For asynchronous text agents and native SDK access:

```xml
<dependency>
    <groupId>io.github.brunoborges</groupId>
    <artifactId>copilot-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

For reactive agent methods, add `copilot-spring-boot-reactor` instead of, or
alongside, the basic starter. For automatic Micrometer setup, add Spring Boot
Actuator. Neither Reactor nor Actuator is required by the basic starter.
Spring Framework can itself bring Micrometer's observation API transitively;
that does not by itself enable this integration's Micrometer instrumentation.

The starter does not install a Copilot executable or select a platform binary.
For local use, configure `copilot.cli-path`, set the SDK's `COPILOT_CLI_PATH`
environment variable, or supply an appropriate SDK runtime classifier artifact.
For example:

```xml
<dependency>
    <groupId>com.github</groupId>
    <artifactId>copilot-sdk-java-runtime</artifactId>
    <version>1.0.24</version>
    <classifier>linux-x64</classifier>
</dependency>
```

Choose the classifier for the deployment platform. Authenticate the CLI using
SDK/CLI-supported authentication before making model requests. An external
server is another option; see [Client properties](configuration.html#client-properties).
<a id="quick-start"></a>

## Quick start

Define a public interface under the Spring Boot application's base package:

```java
package example.agents;

import java.util.concurrent.CompletableFuture;
import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(
        name = "spring-advisor",
        instructions = "Explain Spring Boot concepts concisely.")
public interface SpringAdvisor {
    CompletableFuture<String> answer(
            @ConversationId String conversationId,
            @UserMessage String question);
}
```

Inject `SpringAdvisor` into an ordinary Spring service using constructor
injection. No implementation class is required. For example, from application
code with an injected `advisor`:

```java
var first = advisor.answer("tenant-a/user-42", "What is dependency injection?");
var followUp = first.thenCompose(answer ->
        advisor.answer("tenant-a/user-42", "Summarize your previous answer."));
```

Calls for the same agent and conversation key share persisted conversation
history. Different keys identify independent conversations. The default agent
permission handler rejects permission requests; it does not silently approve
tools.

For business methods without a conversation-key argument, use
[bound agent instances](instances.html) instead of the routed form above.

```yaml
copilot:
  cli-path: /absolute/path/to/copilot
  agents:
    spring-advisor:
      response-timeout: 60s
```

Startup creates the client bean but does not connect by default. The first SDK
operation, including an agent invocation, initiates the connection.

---

**Previous:** [Documentation](index.html)

**Next:** [SDK client](client.html)
