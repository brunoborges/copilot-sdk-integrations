---
title: Conversations and lifecycle
---

<a id="conversations-and-lifecycle"></a>

# Conversations and lifecycle

**In this chapter**

- [Conversation lifecycle](#conversation-lifecycle)
- [Managing sessions](#managing-sessions)
- [Threading, errors, and cancellation](#threading-errors-and-cancellation)

<a id="conversation-lifecycle"></a>

## Conversation lifecycle

For a normal invocation, the extension:

1. Validates the conversation key/message and tries to reserve admission.
2. Serializes behind earlier work for the same agent/conversation.
3. Waits for a global execution slot.
4. Derives a stable SDK session identity from the interface's fully qualified
   name, logical agent name, and conversation key.
5. Uses SDK `getSessionMetadata` to select create or resume. A documented
   null/not-found result selects creation for routed calls or a new handle's
   initial session; strict bound resumption fails instead. Lookup errors propagate.
6. Applies agent settings, permissions, tools, and create/resume customizers.
7. Attaches session listeners, sends the message, and awaits the SDK response.
8. Unsubscribes and closes the session attachment.
9. Releases execution/admission bookkeeping and completes the result.

There is no idle session cache, implicit default conversation, or automatic
retry. Persisted history belongs to the runtime and is kept until deleted through
`CopilotSessionStore` or [age-based cleanup](#managing-sessions).
Changing an interface's fully qualified name or logical agent name changes the
derived identity. Do not depend on the exact digest encoding as an application
API; listeners expose the actual SDK session ID when needed.

All methods on the same agent use the same conversation namespace. Serialization
is local to this application instance, not a distributed lock. Coordinate
ownership across replicas and scope keys to authorized users/tenants yourself.
Resumption requires a runtime supporting the pinned SDK's metadata lookup,
custom IDs, and persistence in the same backing store.

The global bounds are `quarkus.copilot.execution.max-concurrent-invocations`
(default `8`) and `quarkus.copilot.execution.max-outstanding-invocations`
(default `64`); see [Configuration](configuration.html).

<a id="managing-sessions"></a>

## Managing sessions

Inject `io.github.brunoborges.copilot.agent.CopilotSessionStore` to list, read,
and delete the persisted sessions behind conversations:

```java
@Inject CopilotSessionStore sessions;

String id = sessions.sessionId(Advisor.class, conversationId);
Optional<List<CopilotSessionMessage>> history = sessions.messages(id);
boolean deleted = sessions.delete(id);
```

| Method | Behavior |
| --- | --- |
| `list()` | Local agent-created (`agent-…`) sessions, most recently modified first. |
| `get(id)` / `exists(id)` | Session metadata, or empty when the session does not exist. |
| `messages(id)` | User and assistant messages in order, without resuming the session; sub-agent messages and tool calls are omitted. |
| `delete(id)` | Deletes the session and its history after any turn in flight finishes; turns submitted meanwhile wait for the deletion. Returns `false` when the session did not exist. |
| `sessionId(type, conversationId)` | The session ID the agent uses for the conversation on the current thread, including the token resolver's partition. Throws `IllegalArgumentException` for an undiscovered type. |

Every call blocks for at most `quarkus.copilot.sessions.timeout` (default `2m`)
and starts a lazily configured client on first use, so call it from a worker
thread (for example a `@Blocking` endpoint), not the Vert.x event loop. Replace
the bean by declaring your own `CopilotSessionStore`.

Set `quarkus.copilot.sessions.cleanup.max-age` to delete agent sessions not
modified for that long; see [Session properties](configuration.html#session-properties).

<a id="threading-errors-and-cancellation"></a>

## Threading, errors, and cancellation

| Work | Execution context |
| --- | --- |
| Admission and initial telemetry | Calling thread; capacity checks are non-blocking, so routed calls are safe on a Vert.x event loop when the method returns `CompletableFuture` or `Multi`. |
| Agent SDK operation and cleanup | Java virtual-thread execution, bounded by the shared invocation limits. |
| Tool methods, permission handlers, hooks, and native event listeners | SDK callback execution, not the caller's thread. When the caller had an active CDI request context, that context is re-activated around the SDK operation and each callback while it is still valid (and not already active on that thread), so `@RequestScoped` beans (such as the current `SecurityIdentity`) are usable. Other thread-local state (for example MDC or OpenTelemetry context) is not propagated. See [Per-user authentication](authentication.html). |
| Future continuations | Normal `CompletableFuture` rules unless the application supplies an asynchronous executor. |
| Mutiny callbacks | SDK/subscription/operator context; use `emitOn`/`runSubscriptionOn` when a specific executor is required. |

| Condition | Result |
| --- | --- |
| `@CopilotAgent` on a class | Build failure. |
| Invalid signature, missing resource/bean/tool metadata, invalid annotation combination | Application startup failure (agents are validated in a `StartupEvent` observer). |
| Invalid configuration | SmallRye Config mapping or extension validation failure at startup, before use. |
| Blank/null invocation arguments | Failed scalar future, or `Multi` failure on subscription; no SDK operation. |
| Outstanding limit reached | `RejectedExecutionException`; no SDK operation. |
| SDK lookup/create/resume/send failure | Failure propagated through the asynchronous result; no silent new-conversation fallback. |
| Scalar turn has no assistant text | `IllegalStateException`. Event streams can complete normally without text. |
| Bound resume/history continuation cannot find the session | `CopilotConversationNotFoundException`; no replacement conversation is created. |
| Cached bound proxy used after handle close | Failed invocation; a `Multi` fails when subscribed. |
| Listener failure | Logged; other observers continue; otherwise successful invocation fails after cleanup. |
| Response timeout | Failed future/stream with outcome `timeout`; the extension requests a runtime abort of the turn (`CopilotSession.abort()`). It bounds response waiting, not an end-to-end deadline. |
| Stream overflow | `CopilotAgentStreamOverflowException` once a subscriber falls more than `quarkus.copilot.streaming.buffer-capacity` events behind. |
| Call after invoker shutdown | Failed result; queued work not yet executing SDK operations is rejected. |

Asynchronous causes can be wrapped by standard `CompletionException` or
`ExecutionException` when using `join()` or `get()`. Cleanup failures are not
converted to empty successful replies.

Cancelling the returned future (`cancel(...)`), or cancelling a `Multi`
subscription, stops waiting for the reply and asks the runtime to abort the
in-flight turn through `CopilotSession.abort()`. The abort is requested before
the session is closed and waited for up to 10 seconds; an abort failure is
logged at `WARNING` and never replaces the cancellation. The telemetry outcome
is `cancelled`.

| Cancelled when | Effect |
| --- | --- |
| Queued, before SDK work starts | Skipped; no session is opened. |
| While the session is being created/resumed | No message is sent; the session is closed. No abort is needed. |
| While waiting for the reply | Abort, then close. |
| After completion | No-op. |

Cancellation does not release a conversation lock or execution reservation
while actual work still owns resources: the next call on the same conversation
and the shared capacity wait for the abort and session cleanup, and are
released exactly once. Abort is best-effort: tokens already consumed are still
billed, and a runtime may finish a tool call already in progress. Queued calls
skipped before SDK work begins keep their bookkeeping bounded until retirement.
On application shutdown the container disposes the invoker, which rejects new
work and waits for active work and cleanup. There is
no independent hard shutdown deadline that overrides SDK behavior.

---

**Previous:** [Bound instances](instances.html)

**Next:** [System instructions](instructions.html)
