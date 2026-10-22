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
| `copilot-quarkus-parent` | Maven parent and reactor. |
| `copilot-quarkus` | The extension runtime: agent API, configuration, CDI beans, tool binding, and optional integrations (health, Micrometer, info, Mutiny streaming, security, diagnostics endpoint). Brings the Copilot SDK. |
| `copilot-quarkus-deployment` | Build steps: agent discovery and validation, synthetic agent beans, hook catalog, and endpoint routes. Resolved automatically by the Quarkus build; not a direct application dependency. |
| `copilot-quarkus-test` | Test-scoped support: scripted in-memory `CopilotTestRuntime` that replaces the client in `@QuarkusTest`s, and the Testcontainers `CopilotContainerTestResource`. |
| `copilot-quarkus-example` | Quarkus REST example with conversational, bound, and streaming agents. Not published. |

The extension targets Quarkus 3.40, Java 21, and Copilot SDK 1.0.24. No
integration artifacts have been published. Install the reactor locally (from
the `quarkus/` directory) before using its snapshot dependencies in another
application:

```sh
./mvnw --batch-mode --no-transfer-progress install
```

Add the extension to a Quarkus application:

```xml
<dependency>
    <groupId>io.github.brunoborges</groupId>
    <artifactId>copilot-quarkus</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

There is no separate reactive module. Agent methods returning
`Multi<CopilotAgentEvent>` work whenever Mutiny is on the class path (add
`quarkus-mutiny` if nothing else brings it). Health, metrics, info, and security
integrations activate when `quarkus-smallrye-health`, `quarkus-micrometer`,
`quarkus-info`, or a Quarkus Security extension is present; none of them is
required. See [Observability](observability.html) and
[Management](management.html).

The extension does not install a Copilot executable or select a platform binary.
For local use, configure `quarkus.copilot.cli-path`, set the SDK's
`COPILOT_CLI_PATH` environment variable, or supply an appropriate SDK runtime
classifier artifact. For example:

```xml
<dependency>
    <groupId>com.github</groupId>
    <artifactId>copilot-sdk-java-runtime</artifactId>
    <version>1.0.24</version>
    <classifier>linux-x64</classifier>
</dependency>
```

Choose the classifier for the deployment platform. Authenticate the CLI using
SDK/CLI-supported authentication before making model requests, for example by
signing in to the local CLI or supplying an SDK-supported environment variable
such as `COPILOT_GITHUB_TOKEN`. Copilot access requires an appropriate account
entitlement; never commit credentials to source control. **Model requests
consume your account's Copilot usage.** Agents can instead use your own model
provider without a Copilot subscription; see [BYOK providers](byok.html). An
external server is another option; see
[Client properties](configuration.html#client-properties).

<a id="annotation-processing-and-compiler-settings"></a>

### Annotation processing and compiler settings

Tool beans annotated with the SDK's `@CopilotTool` need the SDK annotation
processor; see [CDI tool beans](tools.html#annotation-processing). Prompt
templates that use `@P` without an explicit name need the compiler's
`-parameters` flag (`<parameters>true</parameters>` on `maven-compiler-plugin`).

The extension does not ship `module-info` descriptors; Quarkus applications run
on the class path. Implementation packages such as
`io.github.brunoborges.copilot.agent.internal` and
`io.github.brunoborges.copilot.quarkus.runtime` are not part of the API.

<a id="quick-start"></a>

## Quick start

Define a public interface in the application:

```java
package example.agents;

import java.util.concurrent.CompletableFuture;
import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(
        name = "quarkus-advisor",
        instructions = "Explain Quarkus concepts concisely.")
public interface QuarkusAdvisor {
    CompletableFuture<String> answer(
            @ConversationId String conversationId,
            @UserMessage String question);
}
```

The interface is discovered at build time from the Jandex index; there is no
scanning annotation to add. Inject `QuarkusAdvisor` with `@Inject` (or
constructor injection) into any CDI bean or REST resource. No implementation
class is required:

```java
@Path("/advisor")
public class AdvisorResource {

    @Inject
    QuarkusAdvisor advisor;

    @POST
    @Path("/{conversation}")
    public CompletionStage<String> ask(@PathParam("conversation") String conversation, String question) {
        return advisor.answer(conversation, question);
    }
}
```

From application code with an injected `advisor`:

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

```ini
quarkus.copilot.cli-path=/absolute/path/to/copilot
quarkus.copilot.agents.quarkus-advisor.response-timeout=60s
```

Startup creates the client bean and validates every agent, but does not connect
by default. The first SDK operation, including an agent invocation, initiates
the connection. Set `quarkus.copilot.eager-start=true` to connect during
startup instead.

---

**Previous:** [Documentation](index.html)

**Next:** [SDK client](client.html)
