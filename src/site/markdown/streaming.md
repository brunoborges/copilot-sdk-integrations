---
title: Reactive streaming
---

<a id="reactive-streaming"></a>

# Reactive streaming

Add `io.github.brunoborges:copilot-spring-boot-reactor:0.1.0-SNAPSHOT`.
The basic starter does not include Reactor or a web framework.

```java
import reactor.core.publisher.Flux;
import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotAgentEvent;
import io.github.brunoborges.copilot.agent.UserMessage;

@CopilotAgent(name = "streaming-advisor", instructions = "Explain Java concepts.")
public interface StreamingAdvisor {
    Flux<CopilotAgentEvent> stream(
            @ConversationId String conversationId,
            @UserMessage String question);
}
```

Constructing a `Flux` starts no SDK operation. **Each subscription starts a new
invocation** using the normal conversation and admission rules. Repeated
subscriptions are not automatically shared or replayed.

Events contain the native SDK types, including deltas, final messages, tool,
usage, and session events when emitted by the runtime. Successful completion
follows SDK idle and cleanup; a final assistant text message is not required
for an event stream. SDK and cleanup failures become stream errors.

To consume only text deltas:

```java
Flux<String> deltas = advisor.stream(conversationId, question)
        .map(CopilotAgentEvent::event)
        .ofType(com.github.copilot.generated.AssistantMessageDeltaEvent.class)
        .map(delta -> delta.getData().deltaContent());
```

Do not concatenate both deltas and final message content as if they were
independent text fragments. The module does not add an HTTP controller,
SSE endpoint, authorization policy, or global Reactor hooks.

<a id="buffering-and-cancellation"></a>

## Buffering and cancellation

Observed SDK events are serialized into a bounded per-subscription queue.
On overflow, `io.github.brunoborges.copilot.reactor.CopilotAgentStreamOverflowException`
is delivered after previously buffered events drain. The exception extends
`IllegalStateException`; its message includes the configured capacity.

Overflow and subscriber cancellation stop delivery to that subscriber and
cancel the invocation result future, which asks the runtime to abort the turn
through `CopilotSession.abort()`. Abort is best-effort; configured listeners
and usage instrumentation can still observe events emitted before it lands. Session ownership, serialization, and admission
capacity remain held until actual work/cleanup finish.

Downstream demand is not runtime-generation backpressure. Avoid blocking SDK
callbacks; application operators such as `publishOn` introduce their own
scheduling and buffering behavior.

---

**Previous:** [Configuration reference](configuration.html)

**Next:** [Observability](observability.html)
