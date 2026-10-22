---
title: Bound instances
---

<a id="bound-instances"></a>

# Bound instances

**In this chapter**

- [Using instances from CDI beans](#using-instances-from-cdi-beans)
- [Closing and asynchronous work](#closing-and-asynchronous-work)

`CopilotAgents` and `CopilotAgentInstance<T>` are application-facing APIs in
`io.github.brunoborges.copilot.agent`. Inject the `CopilotAgents` CDI bean:

```java
@CopilotAgent(name = "advisor", instructions = "Explain Quarkus concisely.")
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

`create` and `resume` fail with `IllegalArgumentException` for a type that was
not discovered at build time or that has `@ConversationId` methods (inject
those directly instead).

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

ID-less interfaces are intentionally not CDI beans: `@Inject Advisor` fails the
build with an unsatisfied dependency. Definitions are managed by the extension;
runtime handles are application-owned, not dependent-scoped beans requiring
implicit destruction. The bound business proxy is not itself a CDI bean. Keep
application authorization and transaction boundaries in CDI-managed services;
tool beans are still invoked through their client proxies, so their
interceptors apply.

<a id="using-instances-from-cdi-beans"></a>

## Using instances from CDI beans

CDI has no lookup arguments, so ordinary injection cannot supply a request's
conversation key. Inject `CopilotAgents` into an application bean and bind the
key at call time:

```java
@ApplicationScoped
public class AdvisorService {

    private final CopilotAgents agents;

    AdvisorService(CopilotAgents agents) {
        this.agents = agents;
    }

    public String continueConversation(String conversationId, String question) {
        try (var instance = agents.resume(Advisor.class, conversationId)) {
            return instance.agent().answer(question).join();
        }
    }
}
```

This uses strict resume, not creation of arbitrary new keys. Keep the handle
local to the unit of work and close it there; do not store handles in
`@ApplicationScoped` fields. The example application's `POST /advisor/once`
resource method uses the same pattern with `create`. Because `join()` blocks,
call such methods from a worker or virtual thread, not the Vert.x event loop.

<a id="closing-and-asynchronous-work"></a>

## Closing and asynchronous work

Each method still creates/resumes and detaches an SDK session attachment per
invocation. A handle owns identity and operation lifetime, not an idle live
`CopilotSession` or the shared `CopilotClient`.

Closing is idempotent and blocking. It preserves interrupt status and continues
waiting for cleanup; there is no separate close timeout, and closing does not
abort running turns itself. Cancelling a returned future aborts its turn, but
`close()` still waits for that abort and the session cleanup. Closed cached
proxies reject later invocations; an already constructed `Multi` fails if
subscribed after its handle closes.

Await the entire intended workflow before leaving a try-with-resources block.
`close()` waits for operations already invoked, not future continuations that
have not submitted their next call, or arbitrary downstream event processing.
It does not cancel subscribers or drain their buffers.

Never block in `close()` from SDK listeners, permission/tool/customizer callbacks,
or other work needed to finish the same invocation. Common synchronous paths
are guarded and reject self-closing rather than deadlocking; arbitrary
application asynchronous callbacks are not automatically context-propagated.

ID-less `Multi<CopilotAgentEvent>` methods are supported. For asynchronous
resource cleanup, offload the blocking close rather than closing on an event
loop or SDK callback, for example with Mutiny's resource support:

```java
Multi<CopilotAgentEvent> stream(String question) {
    return Multi.createFrom()
            .resource(() -> agents.create(MyStreamingAdvisor.class),
                    instance -> instance.agent().stream(question))
            .withFinalizer(this::closeOffEventLoop);
}

private Uni<Void> closeOffEventLoop(CopilotAgentInstance<MyStreamingAdvisor> instance) {
    return Uni.createFrom().item(() -> {
                instance.close();
                return instance;
            })
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .replaceWithVoid();
}
```

Here `MyStreamingAdvisor` declares an ID-less
`Multi<CopilotAgentEvent> stream(@UserMessage String question)` method; `Multi`,
`Uni`, and `Infrastructure` are Mutiny types (`io.smallrye.mutiny` and
`io.smallrye.mutiny.infrastructure`). Cancellation cleanup can still wait for
the runtime abort and cleanup. Abort is not a guarantee of billing cancellation.

No new configuration keys are needed for instances. They inherit their
definition's model, instructions, tools, listeners, timeout, telemetry, and
global execution bounds. Keep the routed form when application services prefer
passing an authorized conversation key per call.

---

**Previous:** [Agent interfaces](agents.html)

**Next:** [Conversations and lifecycle](conversations.html)
