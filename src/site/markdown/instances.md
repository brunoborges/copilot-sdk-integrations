---
title: Bound instances
---

<a id="bound-instances"></a>

# Bound instances

**In this chapter**

- [Parameterized Spring lookup](#parameterized-spring-lookup)
- [Closing and asynchronous work](#closing-and-asynchronous-work)

`CopilotAgents` and `CopilotAgentInstance<T>` are application-facing APIs in
`io.github.brunoborges.copilot.agent`. Inject the `CopilotAgents` Spring bean:

```java
@CopilotAgent(name = "advisor", instructions = "Explain Spring Boot concisely.")
public interface Advisor {
    CompletableFuture<String> answer(@UserMessage String question);
}
```

From application code with an injected `agents` provider:

```java
String savedKey;
try (var instance = agents.create(Advisor.class)) {
    var advisor = instance.agent();
    advisor.answer("What is dependency injection?").join();
    advisor.answer("Show an example of that.").join();
    savedKey = instance.conversationId();
}

try (var resumed = agents.resume(Advisor.class, savedKey)) {
    resumed.agent().answer("Continue our discussion.").join();
}
```

| API | Contract |
| --- | --- |
| `<T> CopilotAgentInstance<T> create(Class<T> agentType)` | Bind a fresh generated UUID conversation key. The type must be a discovered ID-less definition. |
| `<T> CopilotAgentInstance<T> resume(Class<T> agentType, String conversationId)` | Bind an existing application conversation key. Blank keys fail immediately; history existence is checked strictly on invocation. |
| `T CopilotAgentInstance.agent()` | Return this handle's bound business proxy while open; throws `IllegalStateException` after closing begins. |
| `String CopilotAgentInstance.conversationId()` | Optional metadata for saving/resuming identity. This is the application key, not the derived SDK session ID, and remains readable after close. |
| `void CopilotAgentInstance.close()` | Reject new work and wait for already admitted operations and cleanup. Does not close the shared client or delete history. |

Creating or resuming a handle does not itself issue SDK requests. There is no
underlying persisted conversation to resume until an operation has opened a
session. Strict resume of missing history fails the operation with
`CopilotConversationNotFoundException`; it never silently creates an empty
conversation. Once a newly created handle has opened a session, its later calls
also require that history to exist, even if the earlier model response failed.
Metadata lookup errors continue to propagate without a fallback.

Separate `create(...)` calls produce separate proxies and conversation keys.
Repeated calls on one handle preserve history. `resume(...)` produces a new
handle/proxy for the saved identity, not the original Java object. Multiple
handles targeting the same agent/key share history and the existing local
serialization rules, but closing one does not close the others.

ID-less interfaces are intentionally not injectable as singleton agent beans.
Definitions are managed by Spring; runtime handles are application-owned,
not prototype beans requiring implicit destruction. The bound business proxy is
not itself registered as a Spring bean. Keep application authorization and
transaction boundaries in Spring-managed services; tool service proxies retain
their normal Spring advice.

<a id="parameterized-spring-lookup"></a>

## Parameterized Spring lookup

Ordinary `@Autowired` injection does not supply a request's conversation key.
Use an application-owned prototype bean when runtime lookup arguments are useful:

```java
@Configuration(proxyBeanMethods = false)
class AdvisorBeans {
    private final CopilotAgents agents;

    AdvisorBeans(CopilotAgents agents) {
        this.agents = agents;
    }

    @Bean
    @Scope("prototype")
    CopilotAgentInstance<Advisor> advisorInstance(String conversationId) {
        return agents.resume(Advisor.class, conversationId);
    }
}
```

Inject `ObjectProvider<CopilotAgentInstance<Advisor>>`, then provide the key
at lookup time:

```java
try (var instance = instances.getObject(conversationId)) {
    instance.agent().answer(question).join();
}
```

The configuration captures its injected dependencies; the factory method's
explicit argument is the runtime key. This adapter uses strict resume, not
creation of arbitrary new keys. Spring does not automatically destroy prototype
beans, so retain and close the handle. Direct
`ObjectProvider<Advisor>.getObject(conversationId)` is not registered by this
integration.

<a id="closing-and-asynchronous-work"></a>

## Closing and asynchronous work

Each method still creates/resumes and detaches an SDK session attachment per
invocation. A handle owns identity and operation lifetime, not an idle live
`CopilotSession` or the shared `CopilotClient`.

Closing is idempotent and blocking. It preserves interrupt status and continues
waiting for cleanup; there is no separate close timeout, and closing does not
abort running turns itself. Cancelling a returned future aborts its turn, but
`close()` still waits for that abort and the session cleanup. Closed cached proxies reject later invocations;
an already constructed cold `Flux` fails if subscribed after its handle closes.

Await the entire intended workflow before leaving a try-with-resources block.
`close()` waits for operations already invoked, not future continuations that
have not submitted their next call, or arbitrary downstream event processing.
It does not cancel subscribers or drain their buffers.

Never block in `close()` from SDK listeners, permission/tool/customizer callbacks,
or other work needed to finish the same invocation. Common synchronous paths
are guarded and reject self-closing rather than deadlocking; arbitrary
application asynchronous callbacks are not automatically context-propagated.

ID-less reactive methods are supported with the optional module. For asynchronous
resource cleanup, offload blocking close rather than closing on an event loop or
SDK callback:

```java
Flux<CopilotAgentEvent> events = Flux.usingWhen(
        Mono.fromSupplier(() -> agents.create(MyStreamingAdvisor.class)),
        instance -> instance.agent().stream(question),
        instance -> Mono.fromRunnable(instance::close)
                .subscribeOn(Schedulers.boundedElastic()));
```

Here `MyStreamingAdvisor` declares an ID-less
`Flux<CopilotAgentEvent> stream(@UserMessage String question)` method; `Mono`,
`Flux`, and `Schedulers` are Reactor types. Cancellation cleanup can still wait
for the runtime abort and cleanup. Abort is not a guarantee of billing cancellation.

No new configuration keys are needed for instances. They inherit their
definition's model, instructions, tools, listeners, timeout, observations, and
global execution bounds. Keep the routed form when application services prefer
passing an authorized conversation key per call.

---

**Previous:** [Agent interfaces](agents.html)

**Next:** [Conversations and lifecycle](conversations.html)
