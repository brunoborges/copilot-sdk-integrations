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

These contracts are in `com.microsoft.copilot.integrations.core`:

| Type | Members |
| --- | --- |
| `CopilotAgentContext` | Record accessors `agentName()`, `conversationId()`, `sessionId()`. |
| `CopilotAgentEvent` | Record accessors `context()` and native SDK `event()`. Used by return-type adapters such as Mutiny. |
| `CopilotAgentListener` | `void onEvent(CopilotAgentContext context, SessionEvent event)`. |

Bind listener beans with `@CopilotAgent(listenerRefs = "agentMessages")`. Each
referenced bean needs an explicit `@Named` value:

```java
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionEvent;
import com.microsoft.copilot.integrations.core.CopilotAgentContext;
import com.microsoft.copilot.integrations.core.CopilotAgentListener;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.jboss.logging.Logger;

@ApplicationScoped
@Named("agentMessages")
public class AgentMessages implements CopilotAgentListener {

    private static final Logger log = Logger.getLogger(AgentMessages.class);

    @Override
    public void onEvent(CopilotAgentContext context, SessionEvent event) {
        if (event instanceof AssistantMessageEvent message) {
            log.infof("%s: %s", context.conversationId(), message.getData().content());
        }
    }
}
```

Callbacks are attached to individual sessions; they are not CDI events fired
through `jakarta.enterprise.event.Event`, and `@Observes` methods do not receive
them. Shared listener instances can receive concurrent calls from different
conversations. Use the supplied context rather than a mutable singleton
"current session."

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

Register a `@Named` bean and select it with
`@CopilotAgent(customizerRefs = "workspace")`. For example, with a CDI producer
method:

```java
@Singleton
public class CustomizerConfiguration {

    @Produces
    @Singleton
    @Named("workspace")
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
}
```

Override both methods when settings or callbacks must be reapplied on resume.
For each invocation, ordering is:

1. Annotation/property settings, default permissions, and declared tools.
2. Customizers in annotation order.
3. Forced SDK streaming for Mutiny/event invocations, when applicable.
4. Optional telemetry wrapping of the final tool handlers.
5. Validation that the managed session ID is unchanged and a permission
   handler remains configured.

Customizers can use SDK capabilities not exposed as declarative properties,
such as advanced MCP authentication settings. Basic server definition
and selection are covered by [MCP servers](mcp.html); tool filtering, permission
policies, and hook beans by [Tool governance, permissions, and hooks](governance.html).
Customizers are responsible for the validity of their advanced SDK
configuration; startup duplicate-tool validation covers declared tool
references, not arbitrary lists constructed later by customizers.

---

**Previous:** [Per-user authentication](authentication.html)

**Next:** [Tool governance, permissions, and hooks](governance.html)
