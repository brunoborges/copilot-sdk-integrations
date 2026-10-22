---
title: Extension SPIs
---

<a id="extension-spis"></a>

# Extension SPIs

**In this chapter**

- [Telemetry](#telemetry)
- [Return-type adapters](#return-type-adapters)

The following interfaces are in `io.github.brunoborges.copilot.agent`, except
`CopilotAgentTelemetryDecorator`, which is in
`io.github.brunoborges.copilot.quarkus.runtime`. Classes in `agent.internal`
and the extension's other runtime and deployment classes are infrastructure,
not application-facing extension contracts. Implementations are CDI beans; the
extension marks beans of these types unremovable, so they need no injection
point.

<a id="telemetry"></a>

## Telemetry

Provide a `CopilotAgentTelemetry` bean to replace the built-in implementation;
the built-in one is a `@DefaultBean`. Without an application bean, the
extension uses the Micrometer implementation when Quarkus Micrometer is present
and `quarkus.copilot.metrics.enabled` is `true` (see
[Observability](observability.html)), otherwise `CopilotAgentTelemetry.NOOP`.

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

To add behavior without replacing the effective telemetry, declare one or more
`CopilotAgentTelemetryDecorator` beans. A decorator is a
`UnaryOperator<CopilotAgentTelemetry>` that receives the effective telemetry
(application, Micrometer, or no-op) and returns a wrapper, for example to
record calls for test assertions:

```java
@Singleton
public class RecordingTelemetry implements CopilotAgentTelemetryDecorator {

    final List<String> started = new CopyOnWriteArrayList<>();

    @Override
    public CopilotAgentTelemetry apply(CopilotAgentTelemetry delegate) {
        return (agent, model) -> {
            started.add(agent);
            return delegate.start(agent, model);
        };
    }
}
```

This lambda keeps only `start`; delegate `rejected` as well when it matters.
Decorators are applied once, when the invoker is created.

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

Register adapters as CDI beans (for example `@Singleton`). Matching is based on
the declared method return type and must be unique; multiple matches fail
initialization rather than selecting one by `@Priority`. Without a matching
adapter, `CompletableFuture<T>` and synchronous `T` use the built-in text and
structured-result paths (see [Agent interfaces](agents.html#return-types)).

When Mutiny is on the class path, the extension registers
`io.github.brunoborges.copilot.quarkus.runtime.mutiny.MutinyCopilotAgentAdapter`,
matching exactly `Multi<CopilotAgentEvent>` (see [Mutiny streaming](streaming.html)).
It has no switch and is not a `@DefaultBean`, so an application adapter that
also matches `Multi<CopilotAgentEvent>` fails initialization; adapters for other
return types (for example `Uni<...>` or a custom type) coexist with it.
Adapters receive text-only invocations; structured results are only available
through the built-in `CompletableFuture<T>` and synchronous return types.

---

**Previous:** [Health, info, and endpoint](management.html)

**Next:** [Testing](testing.html)
