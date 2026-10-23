# Mutiny streaming

Agent methods can return `io.smallrye.mutiny.Multi<CopilotAgentEvent>`. There is
no separate streaming module: the extension registers its
`MutinyCopilotAgentAdapter` whenever Mutiny (`io.quarkus:quarkus-mutiny`) is on
the class path, which is the case for most Quarkus applications, including any
that use Quarkus REST. Without Mutiny, `Multi` return types are rejected like
any other unsupported return type.

```java
import io.smallrye.mutiny.Multi;
import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.CopilotAgentEvent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "streaming-advisor", instructions = "Explain Java concepts.")
public interface StreamingAdvisor {
    Multi<CopilotAgentEvent> stream(
            @ConversationId String conversationId,
            @UserMessage String question);
}
```

Constructing a `Multi` starts no SDK operation. **Each subscription starts a new
invocation** using the normal conversation and admission rules. Repeated
subscriptions are not automatically shared or replayed.

Events contain the native SDK types, including deltas, final messages, tool,
usage, and session events when emitted by the runtime. Successful completion
follows SDK idle and cleanup; a final assistant text message is not required
for an event stream. SDK and cleanup failures become stream failures, unwrapped
from any `CompletionException`.

To consume only text deltas:

```java
import com.github.copilot.generated.AssistantMessageDeltaEvent;

Multi<String> deltas = advisor.stream(conversationId, question)
        .map(CopilotAgentEvent::event)
        .filter(AssistantMessageDeltaEvent.class::isInstance)
        .map(event -> ((AssistantMessageDeltaEvent) event).getData().deltaContent());
```

Do not concatenate both deltas and final message content as if they were
independent text fragments. The extension does not add an HTTP resource, SSE
endpoint, or authorization policy, but Quarkus REST can return a `Multi`
directly as server-sent events. The [example application](examples.html) streams
final assistant messages this way:

```java
@POST
@Path("/{conversation}/stream")
@Produces(MediaType.SERVER_SENT_EVENTS)
@RestStreamElementType(MediaType.TEXT_PLAIN)
public Multi<String> stream(@PathParam("conversation") String conversation, String question) {
    return streaming.stream(conversation, question)
            .map(CopilotAgentEvent::event)
            .filter(AssistantMessageEvent.class::isInstance)
            .map(event -> ((AssistantMessageEvent) event).getData().content());
}
```

<a id="buffering-and-cancellation"></a>

## Buffering and cancellation

Observed SDK events are buffered per subscription, up to
`quarkus.copilot.streaming.buffer-capacity` events (default `256`; it must be
positive). On overflow,
`com.microsoft.copilot.integrations.quarkus.runtime.mutiny.CopilotAgentStreamOverflowException`
is delivered after previously buffered events drain; it replaces Mutiny's
`BackPressureFailure`. The exception extends `IllegalStateException`; its
message includes the configured capacity.

```ini
quarkus.copilot.streaming.buffer-capacity=1024
```

Overflow, failure, and subscriber cancellation all terminate the stream and
cancel the invocation result future if it is still running, which asks the
runtime to abort the turn through `CopilotSession.abort()`. Abort is
best-effort; configured listeners and usage instrumentation can still observe
events emitted before it lands. Session ownership, serialization, and admission
capacity remain held until actual work/cleanup finish.

Downstream demand is not runtime-generation backpressure. Avoid blocking SDK
callbacks; application operators such as `emitOn` or
`onOverflow().buffer()` introduce their own scheduling and buffering behavior.

---

**Previous:** [Configuration reference](configuration.html)

**Next:** [Observability](observability.html)
