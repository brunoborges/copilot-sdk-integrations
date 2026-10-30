# Agents and conversations

## Prompts and results

The body is converted to a string and sent to the agent. Set `prompt` to a Camel
Simple expression to select a field instead. A null or blank prompt fails the exchange.
The producer is asynchronous; use Camel error handlers around it as with other route steps.

```java
from("direct:triage")
    .to("copilot:agent:triage"
        + "?instructions=Classify the support ticket."
        + "&prompt=${body.text}"
        + "&outputClass=com.acme.Triage");
```

Without `outputClass`, the response body is text. With it, the shared engine requests
structured output and converts the response to that class. For example:

```java
package com.acme;

public record Triage(String severity, String summary) {}
```

## Continue a conversation

Set `CopilotConversationId` on incoming exchanges, or configure the `conversationId`
Simple expression. Reusing the identifier for an agent reuses its Copilot session.
Without an identifier, the exchange is a one-shot conversation and its session is
deleted afterward.

```java
from("direct:ticket")
    .setHeader("CopilotConversationId", simple("${header.ticketId}"))
    .to("copilot:agent:support?instructions=Help resolve this ticket.");
```

Choose identifiers scoped to the authorized user or tenant. A conversation ID is
not an authorization boundary by itself.

## Declarative interfaces

Declare an agent with the shared core annotations:

```java
package com.acme;

import com.microsoft.copilot.integrations.core.ConversationId;
import com.microsoft.copilot.integrations.core.CopilotAgent;
import com.microsoft.copilot.integrations.core.UserMessage;

@CopilotAgent(name = "advisor", instructions = "You are a Camel expert.",
              builtInTools = false)
public interface Advisor {
    String ask(@ConversationId String conversationId, @UserMessage String question);
}
```

Register interface names before starting the context:

```java
CopilotComponent copilot = context.getComponent("copilot", CopilotComponent.class);
copilot.setAgentTypes("com.acme.Advisor");
```

```java
from("direct:advice").to("copilot:agent:advisor");
```

Agent-definition options belong on the interface, not on the endpoint referencing
it. Mixing them fails with a configuration error. Routing options such as `prompt`
and `conversationId` still belong on the endpoint.

Explicit-ID agents are also bound under their name in the registry. The
`copilotAgents` registry bean creates and resumes other agent instances.

## Session administration

After the component starts, obtain its session store (also bound as
`copilotSessionStore`):

```java
CopilotSessionStore sessions =
    context.getComponent("copilot", CopilotComponent.class).sessionStore();
String id = sessions.sessionId(Advisor.class, conversationId);
sessions.messages(id).ifPresent(history ->
    history.forEach(message -> log.info(message.role() + ": " + message.content())));
sessions.delete(id);
```

`list()` returns agent-created sessions, newest first, including endpoint agents.
Deletion waits for an in-flight turn on that session. Treat stored messages as
sensitive application data; avoid logging their content in production.

Set component `sessionMaxAge`, for example `30d`, to expire sessions that have not
been modified for that period. Cleanup runs on a Camel-managed scheduled thread,
does not start the client, and skips sessions with a turn in progress. See
[configuration](configuration.html) for cleanup timing controls.

## Message provenance

`messageSource` accepts `user`, `system`, or `agent-<id>`. With no endpoint setting
or active `CopilotMessageSources` scope, prompts originating from `timer:`,
`scheduler:`, `quartz:`, or `cron:` routes are labeled `system`; other routes leave
the runtime default (`user`) unchanged. This label is caller-supplied provenance,
never an authenticated identity or permission grant.
