---
title: Java (Spring Boot and Quarkus)
---

# Java (Spring Boot and Quarkus)

Java services connect to the AppHost's runtime through the
[Spring Boot starter](https://brunoborges.github.io/copilot-sdk-integrations/spring-boot/) or the
[Quarkus extension](https://brunoborges.github.io/copilot-sdk-integrations/quarkus/). Both can use
an external runtime instead of starting their own: set a CLI URL and a connection token, and map
the model to your agents. Aspire has no client package for Java. The app reads the injected
environment variables through its normal configuration.

## AppHost

The [Aspire Java hosting integration](https://aspire.dev/integrations/frameworks/java/java-host/)
(`Aspire.Hosting.Java`) runs Spring Boot and Quarkus apps. Add it to the AppHost:

```bash
aspire add java
```

```cs
var copilot = builder.AddCopilot("copilot");
var chat = copilot.AddModel("chat", "gpt-5.4");

builder.AddSpringBootApp("orders", "../orders")
       .WithReference(chat)
       .WaitFor(chat);

builder.AddQuarkusApp("catalog", "../catalog")
       .WithReference(chat)
       .WaitFor(chat);
```

These methods run the app with its Maven or Gradle wrapper (`mvnw` or `gradlew`), so the project
needs one. `AddJavaApp` runs a JAR, and `AddJavaContainer` runs an image. You can also use the
generic `AddExecutable`, `AddContainer`, or `AddDockerfile`; `WithReference(chat)` injects the same
variables into any of them.

Both apps receive:

| Variable | Example value |
| --- | --- |
| `CHAT_HOST` | `localhost` |
| `CHAT_PORT` | `5000` |
| `CHAT_CONNECTIONTOKEN` | The generated token |
| `CHAT_MODEL` | `gpt-5.4` |

## Spring Boot

Add the starter:

```xml
<dependency>
    <groupId>com.microsoft</groupId>
    <artifactId>copilot-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Map the variables in `application.properties`:

```properties
copilot.cli-url=${CHAT_HOST}:${CHAT_PORT}
copilot.tcp-connection-token=${CHAT_CONNECTIONTOKEN}
copilot.agents.support.model=${CHAT_MODEL}
```

Spring Boot resolves environment variables in `${...}` placeholders. `support` is the name of a
`@CopilotAgent`:

```java
import java.util.concurrent.CompletableFuture;
import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "support", instructions = "You help customers with orders.")
public interface SupportAgent {
    CompletableFuture<String> answer(@ConversationId String conversationId, @UserMessage String question);
}
```

| Property | Notes |
| --- | --- |
| `copilot.cli-url` | A port, `host:port`, or `http(s)://host:port`. **Don't use `CHAT_ENDPOINT`**: its `tcp://` scheme is rejected. Can't be combined with `cli-path`, `cli-args`, or `working-directory`. |
| `copilot.tcp-connection-token` | The runtime's connection token. Requires `cli-url`. |
| `copilot.agents.<name>.model` | The model for one agent. |
| `copilot.agents.<name>.reasoning-effort` | Set it from `${Copilot__ReasoningEffort}` when the AppHost uses `WithReasoningEffort`. |

### `CopilotConnectionDetails`

Instead of properties, you can supply the connection in code with a `CopilotConnectionDetails`
bean. When one is present, it takes precedence over `copilot.cli-url` and
`copilot.tcp-connection-token`:

```java
import com.microsoft.copilot.integrations.springboot.autoconfigure.CopilotConnectionDetails;

@Bean
CopilotConnectionDetails copilotConnectionDetails(Environment env) {
    return new CopilotConnectionDetails() {
        @Override public String cliUrl() {
            return env.getRequiredProperty("CHAT_HOST") + ":" + env.getRequiredProperty("CHAT_PORT");
        }
        @Override public String tcpConnectionToken() {
            return env.getRequiredProperty("CHAT_CONNECTIONTOKEN");
        }
    };
}
```

## Quarkus

Add the extension:

```xml
<dependency>
    <groupId>com.microsoft</groupId>
    <artifactId>copilot-quarkus</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Map the variables in `application.properties`:

```properties
quarkus.copilot.cli-url=${CHAT_HOST}:${CHAT_PORT}
quarkus.copilot.tcp-connection-token=${CHAT_CONNECTIONTOKEN}
quarkus.copilot.agents.support.model=${CHAT_MODEL}
```

| Property | Notes |
| --- | --- |
| `quarkus.copilot.cli-url` | A port, `host:port`, or `http(s)://host:port`. `tcp://` is rejected, so don't use `CHAT_ENDPOINT`. |
| `quarkus.copilot.tcp-connection-token` | The runtime's connection token. Requires `cli-url`. |
| `quarkus.copilot.agents.<name>.model` | The model for one agent. |
| `quarkus.copilot.agents.<name>.reasoning-effort` | The reasoning effort for one agent. |

## MCP servers

When the AppHost calls `WithMcpServer`, map the injected URL to a server and select it per agent:

```properties
copilot.mcp.servers.docs.type=http
copilot.mcp.servers.docs.url=${Copilot__McpServers__docs__Url}
copilot.mcp.servers.docs.tools=search_docs,get_page
copilot.agents.support.mcp-servers=docs
```

Quarkus uses the same keys under `quarkus.copilot.`. See [MCP servers](mcp-servers.md).

## BYOK providers

Provider settings are only in the connection string (`ConnectionStrings__chat`), not in separate
variables. Parse it at startup, or set the provider properties from your own parameters.
The Spring Boot and Quarkus provider properties are `copilot.providers.<name>.type`, `base-url`,
`api-key`, and `wire-api` (`quarkus.copilot.providers.<name>.*` in Quarkus). For a runtime that
serves only BYOK models, set `copilot.health.require-authentication=false` in Spring Boot so the
health indicator doesn't report `DOWN` when the runtime isn't signed in to GitHub. See
[Models and BYOK providers](models.md#bring-your-own-key-providers).

## Authentication

Don't configure a GitHub token in the Java app. The runtime that the AppHost runs signs in to
GitHub; the app only sends the connection token.
