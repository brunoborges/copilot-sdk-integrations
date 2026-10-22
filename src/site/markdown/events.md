---
title: Events and customizers
---

<a id="events-and-customizers"></a>

# Events and customizers

**In this chapter**

- [Listeners and event types](#listeners-and-event-types)
- [Session customizers](#session-customizers)

<a id="listeners-and-event-types"></a>

## Listeners and event types

These contracts are in `io.github.brunoborges.copilot.agent`:

| Type | Members |
| --- | --- |
| `CopilotAgentContext` | Record accessors `agentName()`, `conversationId()`, `sessionId()`. |
| `CopilotAgentEvent` | Record accessors `context()` and native SDK `event()`. Used by return-type adapters such as Reactor. |
| `CopilotAgentListener` | `void onEvent(CopilotAgentContext context, SessionEvent event)`. |

Bind listener bean names with `@CopilotAgent(listeners = "agentMessages")`:

```java
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionEvent;
import io.github.brunoborges.copilot.agent.CopilotAgentContext;
import io.github.brunoborges.copilot.agent.CopilotAgentListener;
import org.springframework.stereotype.Component;

@Component("agentMessages")
public class AgentMessages implements CopilotAgentListener {
    @Override
    public void onEvent(CopilotAgentContext context, SessionEvent event) {
        if (event instanceof AssistantMessageEvent message) {
            System.out.println(context.conversationId() + ": " + message.getData().content());
        }
    }
}
```

Callbacks are attached to individual sessions; they are not broadcasts through
Spring's `ApplicationEventPublisher`. Shared listener instances can receive
concurrent calls from different conversations. Use the supplied context rather
than a mutable singleton "current session."

Listed observers continue after a listener exception. Exceptions are logged,
and an otherwise successful invocation fails after cleanup if an observer
failed. Delivery is not guaranteed for events emitted before attachment or for
callbacks already in flight during closing. Listeners must not block waiting
for another invocation on the same serialized conversation.

<a id="session-customizers"></a>

## Session customizers

`CopilotAgentCustomizer` has two default no-op methods:

```java
void customize(CopilotAgentContext context, SessionConfig config);
void customize(CopilotAgentContext context, ResumeSessionConfig config);
```

Register a bean and select it with `@CopilotAgent(customizers = "workspace")`.
For example, inside a configuration class:

```java
@Bean
CopilotAgentCustomizer workspace() {
    return new CopilotAgentCustomizer() {
        @Override
        public void customize(CopilotAgentContext context, SessionConfig config) {
            config.setWorkingDirectory("/srv/approved-workspace");
        }

        @Override
        public void customize(CopilotAgentContext context, ResumeSessionConfig config) {
            config.setWorkingDirectory("/srv/approved-workspace");
        }
    };
}
```

Override both methods when settings or callbacks must be reapplied on resume.
For each invocation, ordering is:

1. Annotation/property settings, default permissions, and declared tools.
2. Customizers in annotation order.
3. Forced SDK streaming for reactive/event invocations, when applicable.
4. Optional telemetry wrapping of the final tool handlers.
5. Validation that the managed session ID is unchanged and a permission
   handler remains configured.

Customizers can use SDK capabilities not exposed as declarative properties,
such as hooks or advanced MCP authentication settings. Basic server definition
and selection are covered by [MCP servers](mcp.html). Customizers are responsible for the validity of their
advanced SDK configuration; startup duplicate-tool validation covers declared
tool references, not arbitrary lists constructed later by customizers.

---

**Previous:** [MCP servers](mcp.html)

**Next:** [Configuration reference](configuration.html)
