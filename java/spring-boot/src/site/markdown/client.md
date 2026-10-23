---
title: SDK client
---

<a id="sdk-client"></a>

# SDK client

**In this chapter**

- [SDK client API](#sdk-client-api)
- [Auto-configuration and bean overrides](#auto-configuration-and-bean-overrides)

<a id="sdk-client-api"></a>

## SDK client API

The starter supplies these native SDK beans:

| Bean name | Type | Behavior |
| --- | --- | --- |
| `copilotClientOptions` | `com.github.copilot.rpc.CopilotClientOptions` | Maps supported client properties unless an options or client bean replaces that configuration. |
| `copilotClient` | `com.github.copilot.CopilotClient` | Shared connection owner, closed by Spring when supplied by the starter. |

Use the SDK directly when the typed agent contract is not appropriate:

```java
import com.github.copilot.CopilotClient;
import org.springframework.stereotype.Service;

@Service
public class RuntimeProbe {
    private final CopilotClient client;

    public RuntimeProbe(CopilotClient client) {
        this.client = client;
    }

    public java.util.concurrent.CompletableFuture<?> ping() {
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

<a id="auto-configuration-and-bean-overrides"></a>

## Auto-configuration and bean overrides

| Configuration class | Activation and backoff |
| --- | --- |
| `CopilotAutoConfiguration` | SDK client class present, `copilot.enabled=true` or absent, and no user `CopilotClient` bean. |
| `CopilotAgentsAutoConfiguration` | SDK/client bean available and integration enabled. Creates the invoker, `CopilotAgents` provider, and no-op telemetry when not otherwise supplied. |
| `CopilotAgentObservationAutoConfiguration` | SDK and Micrometer observation/core classes present, observation registry available, both enable flags active, and no user telemetry bean. |
| `CopilotReactorAutoConfiguration` | Reactor module and SDK/Flux classes present, integration enabled; backs off for a user `ReactorCopilotAgentAdapter`. |

The first three are under `com.microsoft.copilot.integrations.springboot.autoconfigure`; the
last is under `com.microsoft.copilot.integrations.springboot.reactor`.

The provider bean is named `copilotAgents` and backs off for an
application-provided `CopilotAgents` implementation. Discovered ID-less
definitions retain their logical bean names as internal definition metadata,
not as singleton business-interface instances.

<a id="native-client-options"></a>

### Native client options

Providing a `CopilotClientOptions` bean replaces client connection-property
mapping. For example, inside a configuration class:

```java
@Bean
CopilotClientOptions copilotClientOptions() {
    return new CopilotClientOptions()
            .setCliPath("/absolute/path/to/copilot")
            .setLogLevel("debug");
}
```

`eager-start` and `startup-timeout` still control the starter-owned client's
initialization. SDK options such as `autoStart` retain the value you supply;
turning off SDK automatic startup requires an explicit startup strategy.

<a id="native-client-ownership"></a>

### Native client ownership

Providing a typed `CopilotClient` bean makes client auto-configuration back off:
it does not create its options bean, start/reconfigure that client, or add its
own cleanup callback. Typed agents can still use the supplied client.

Spring normally infers `close()` as the destroy method on a user `@Bean`.
Disable that inference only when another owner actually manages the client's
lifecycle. Use precise `@Bean` return types so Boot can detect replacement beans
before instantiation.

The starter-owned client is created eagerly as a bean even with global lazy
initialization, but connection remains lazy unless eager startup is requested.
Eager startup failure closes the partially initialized client and fails context
startup. The timeout bounds the connection wait, not all cleanup. Normal client
shutdown delegates to SDK `close()`, whose own internal failures are logged at
Java logging level `FINE` rather than propagated by that SDK API.

---

**Previous:** [Getting started](getting-started.html)

**Next:** [Agent interfaces](agents.html)
