---
title: Conversations and lifecycle
---

<a id="conversations-and-lifecycle"></a>

# Conversations and lifecycle

**In this chapter**

- [Conversation lifecycle](#conversation-lifecycle)
- [Threading, errors, and cancellation](#threading-errors-and-cancellation)

<a id="conversation-lifecycle"></a>

## Conversation lifecycle

For a normal invocation, the integration:

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

There is no idle session cache, implicit default conversation, automatic retry,
or automatic history deletion. Persisted history belongs to the runtime.
Changing an interface's fully qualified name or logical agent name changes the
derived identity. Do not depend on the exact digest encoding as an application
API; listeners expose the actual SDK session ID when needed.

All methods on the same agent use the same conversation namespace. Serialization
is local to this application context, not a distributed lock. Coordinate
ownership across replicas and scope keys to authorized users/tenants yourself.
Resumption requires a runtime supporting the pinned SDK's metadata lookup,
custom IDs, and persistence in the same backing store.
<a id="threading-errors-and-cancellation"></a>

## Threading, errors, and cancellation

| Work | Execution context |
| --- | --- |
| Admission and initial observation | Calling thread; capacity checks are non-blocking. |
| Agent SDK operation and cleanup | Java virtual-thread execution, bounded by the shared invocation limits. |
| Tool methods and native event listeners | SDK callback execution; not implicitly a Spring web request scope. |
| Future continuations | Normal `CompletableFuture` rules unless the application supplies an asynchronous executor. |
| Reactive callbacks | SDK/subscription/operator context; use explicit Reactor scheduling when appropriate. |

| Condition | Result |
| --- | --- |
| Invalid signature, duplicate name, missing resource/bean/tool metadata | Initialization failure, normally wrapped by Spring bean creation. |
| Invalid bound configuration | Spring configuration-binding/validation failure before use. |
| Blank/null invocation arguments | Failed scalar future, or reactive error on subscription; no SDK operation. |
| Outstanding limit reached | `RejectedExecutionException`; no SDK operation. |
| SDK lookup/create/resume/send failure | Failure propagated through the asynchronous result; no silent new-conversation fallback. |
| Scalar turn has no assistant text | `IllegalStateException`. Event streams can complete normally without text. |
| Bound resume/history continuation cannot find the session | `CopilotConversationNotFoundException`; no replacement conversation is created. |
| Cached bound proxy used after handle close | Failed invocation; a cold stream errors when subscribed. |
| Listener failure | Logged; other observers continue; otherwise successful invocation fails after cleanup. |
| Response timeout | Failed future/stream; it is not an end-to-end deadline or guaranteed runtime abort. |
| Stream overflow | `CopilotAgentStreamOverflowException` after buffered events drain. |
| Call after invoker shutdown | Failed result; queued work not yet executing SDK operations is rejected. |

Asynchronous causes can be wrapped by standard `CompletionException` or
`ExecutionException` when using `join()` or `get()`. Cleanup failures are not
converted to empty successful replies.

Cancellation does not release a conversation lock or execution reservation
while actual work still owns resources. Queued calls are skipped when
cancellation is observed before SDK work begins; their bookkeeping remains
bounded until retirement. Shutdown rejects new work and waits for active work
and cleanup. There is no independent hard shutdown deadline that overrides SDK
behavior.

---

**Previous:** [Bound instances](instances.html)

**Next:** [System instructions](instructions.html)
