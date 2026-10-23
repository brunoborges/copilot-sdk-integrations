---
title: SDK client
---

<a id="sdk-client"></a>

# SDK client

**In this chapter**

- [SDK client API](#sdk-client-api)
- [Default beans and overrides](#default-beans-and-overrides)

<a id="sdk-client-api"></a>

## SDK client API

The extension supplies these native SDK beans:

| Type | Scope | Behavior |
| --- | --- | --- |
| `com.github.copilot.rpc.CopilotClientOptions` | `@Singleton`, `@DefaultBean` | Maps and validates the `quarkus.copilot.*` client properties unless the application supplies its own options or client bean. |
| `com.github.copilot.CopilotClient` | `@Singleton`, `@DefaultBean` | Shared connection owner, built from the options bean and closed by the container on shutdown. |

Use the SDK directly when the typed agent contract is not appropriate:

```java
import java.util.concurrent.CompletableFuture;

import jakarta.enterprise.context.ApplicationScoped;

import com.github.copilot.CopilotClient;

@ApplicationScoped
public class RuntimeProbe {
    private final CopilotClient client;

    public RuntimeProbe(CopilotClient client) {
        this.client = client;
    }

    public CompletableFuture<?> ping() {
        return client.ping("hello");
    }
}
```

`CopilotClient`, `CopilotSession`, `SessionConfig`, `ResumeSessionConfig`,
`SystemMessageConfig`, and `ToolDefinition` are SDK APIs, not reimplementations
provided by this project. Consult the
[Java SDK documentation](https://github.com/github/copilot-sdk/tree/main/java)
for their complete contracts.

Close sessions and subscriptions created directly by application code. Do not
close the shared injected client after each request.

<a id="default-beans-and-overrides"></a>

## Default beans and overrides

Wiring happens at build time. When `quarkus.copilot.enabled` is `true` (the
default), the deployment processor registers `CopilotProducers` and
`CopilotAgentRegistry` and adds the optional integrations whose extensions are
present. With `quarkus.copilot.enabled=false` no Copilot beans, agents, or
integrations are registered.

| Bean type | Producer | Override |
| --- | --- | --- |
| `CopilotClientOptions` | `CopilotProducers`, `@DefaultBean` | Declare your own `CopilotClientOptions` bean. |
| `CopilotClient` | `CopilotProducers`, `@DefaultBean` | Declare your own `CopilotClient` bean. |
| `CopilotAgents` | `CopilotProducers`, `@DefaultBean` | Declare your own `CopilotAgents` bean. |
| `CopilotAgentTelemetry` | `CopilotProducers`, `@DefaultBean`; Micrometer-backed when that integration is active, otherwise a no-op | Declare your own `CopilotAgentTelemetry` bean. |
| `CopilotAgentInvoker` (internal) | `CopilotProducers` | Not replaceable; wrap telemetry with a `CopilotAgentTelemetryDecorator` bean instead. |

All of these live in the `com.microsoft.copilot.integrations.quarkus.runtime`
package, except the API types in `com.microsoft.copilot.integrations.core`. A
`@DefaultBean` is used only when the application declares no other bean of the
same type, so overriding needs no extra annotation. Explicit-ID agent
interfaces become `@Singleton` synthetic beans; ID-less (bound) definitions are
not beans at all (see [Bound instances](instances.html)).

<a id="native-client-options"></a>

### Native client options

Providing a `CopilotClientOptions` bean replaces client connection-property
mapping (`cli-path`, `cli-args`, `working-directory`, `log-level`, `cli-url`,
`tcp-connection-token`). For example:

```java
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import com.github.copilot.rpc.CopilotClientOptions;

@Singleton
public class CopilotOptionsProducer {

    @Produces
    @Singleton
    CopilotClientOptions copilotClientOptions() {
        return new CopilotClientOptions()
                .setCliPath("/absolute/path/to/copilot")
                .setLogLevel("debug");
    }
}
```

The default client is still built from your options bean, closed on shutdown,
and controlled by `quarkus.copilot.eager-start` and
`quarkus.copilot.startup-timeout`. SDK options such as `autoStart` retain the
value you supply; turning off SDK automatic startup requires an explicit
startup strategy.

<a id="native-client-ownership"></a>

### Native client ownership

Providing a `CopilotClient` bean replaces the default client (and the options
bean is then unused by the extension). Typed agents, health, and the
diagnostics endpoint use the supplied client.

The extension's disposer only applies to the client it produces. Close a client
you produce yourself with your own `@Disposes` method, unless another owner
manages its lifecycle:

```java
@Singleton
public class CopilotClientProducer {

    @Produces
    @Singleton
    CopilotClient copilotClient() {
        return new CopilotClient(new CopilotClientOptions().setCliUrl("localhost:4321"));
    }

    void close(@Disposes CopilotClient client) {
        client.close();
    }
}
```

`quarkus.copilot.eager-start=true` starts whichever `CopilotClient` bean is
active, including an application-supplied one.

The client bean is created during application startup, because the extension
validates every agent and optionally connects in a `StartupEvent` observer, but
the connection remains lazy unless eager startup is requested. Eager startup
failure (including exceeding `startup-timeout`) closes the client and fails
application startup. The timeout bounds the connection wait, not all cleanup.
Normal client shutdown delegates to SDK `close()`, whose own internal failures
are logged at Java logging level `FINE` rather than propagated by that SDK API.

---

**Previous:** [Getting started](getting-started.html)

**Next:** [Coming from Spring Boot](spring-boot.html)
