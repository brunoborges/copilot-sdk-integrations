---
title: Extension SPIs
---

<a id="extension-spis"></a>

# Extension SPIs

**In this chapter**

- [Telemetry](#telemetry)
- [Return-type adapters](#return-type-adapters)

The following interfaces are in `com.microsoft.copilot.integrations.core`.
Classes in `agent.internal` and auto-configuration implementation classes are
infrastructure, not application-facing extension contracts.

<a id="telemetry"></a>

## Telemetry

Provide a `CopilotAgentTelemetry` bean to replace the built-in implementation.
`CopilotAgentTelemetry.NOOP` is the no-op implementation.

| Method | Contract |
| --- | --- |
| `Call start(String agent, String model)` | Start instrumentation for an accepted invocation, before queue wait. Return a non-null per-call handle. |
| `void rejected(String agent)` | Optional callback for capacity rejection. Default is no-op. |
| `Call.openScope()` | Returns a `CopilotAgentTelemetry.Scope` to bind context while work executes. |
| `Call.tool(String name)` | Create child tool instrumentation. Default returns `Call.NOOP`. |
| `Call.usage(AssistantUsageEvent event)` | Observe native usage events. Default is no-op. |
| `Call.error(Throwable error)` | Record operation failure. Default is no-op. |
| `Call.cancelled()` | Record caller cancellation. Default is no-op. |
| `Call.stop()` | End instrumentation. Make implementations idempotent. |
| `Scope.close()` | Release bound instrumentation context; no checked exception is declared. |

Implementations must be lightweight and thread-safe, and should not throw.
Callbacks can occur on the caller, execution, SDK, or future-completion threads.
Instrumentation failures can fail the invocation; they are not a request to
retry an already executed operation. Shutdown/admission cleanup still applies.

<a id="return-type-adapters"></a>

## Return-type adapters

`CopilotAgentReturnTypeAdapter` declares:

```java
boolean supports(java.lang.reflect.Type returnType);
Object adapt(CopilotAgentInvocation invocation);
```

`CopilotAgentInvocation` declares:

```java
CompletableFuture<?> invoke(java.util.function.Consumer<CopilotAgentEvent> events);
```

An adapter supplies an event consumer when ready to start one invocation.
The returned future signals completion/error; adapters must not require a
non-null text result. It does not expose a raw session or grant ownership of
the shared client.

Register adapters as Spring beans. Matching is based on the declared method
return type and must be unique; multiple matches fail initialization rather
than selecting one by `@Order`. Without a matching adapter,
`CompletableFuture<T>` and synchronous `T` use the built-in text and
structured-result paths (see [Agent interfaces](agents.html#return-types)).

The Reactor module registers `ReactorCopilotAgentAdapter`, matching exactly
`Flux<CopilotAgentEvent>`. To replace that representation with another adapter,
replace the default adapter bean or exclude
`com.microsoft.copilot.integrations.springboot.reactor.CopilotReactorAutoConfiguration` before
registering a competing implementation. Adapters receive text-only
invocations; structured results are only available through the built-in
`CompletableFuture<T>` and synchronous return types.

---

**Previous:** [Actuator health, info, and endpoint](actuator.html)

**Next:** [Example application](examples.html)
