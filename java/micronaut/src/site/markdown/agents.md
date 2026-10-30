---
title: Agent interfaces
---

<a id="agent-interfaces"></a>

# Agent interfaces

An agent is a Java interface annotated with `@CopilotAgent`. The
`copilot-micronaut-processor` annotation processor registers each interface at
compile time; the integration supplies the implementation.

<a id="return-types"></a>

## Return types

Agent methods may return:

- `CompletableFuture<String>` for the final assistant message;
- `CompletableFuture<T>` for a structured result mapped to `T`;
- synchronous `String` or `T`, which block the caller;
- `Flux<CopilotAgentEvent>` for native session events (requires `micronaut-reactor`).

Cancelling a future or `Flux`, or hitting the agent's response timeout, aborts the
in-flight runtime turn.

<a id="routing-by-conversation"></a>

## Routing by conversation

Interfaces with a `@ConversationId` parameter are singleton beans. Each call is
routed to the persisted Copilot session for that conversation, which is created on
first use and resumed afterwards. Turns on the same conversation are serialized,
and admission across all agents is bounded and non-blocking (see
`micronaut.copilot.execution.*` in [Configuration](configuration.html)).

```java
@CopilotAgent(name = "micronaut-advisor",
        instructions = "Explain Micronaut concepts concisely using Java examples.")
public interface MicronautAdvisor {
    CompletableFuture<String> answer(@ConversationId String conversationId, @UserMessage String question);
}
```

<a id="bound-instances"></a>

## Bound instances

Interfaces without a `@ConversationId` parameter are not beans. Create
application-owned instances with `CopilotAgents.create(Type.class)` or resume one
with `CopilotAgents.resume(Type.class, id)`, and close them when done:

```java
@CopilotAgent(name = "bound-advisor", systemMessageRef = "advisorInstructions",
        permissionHandlerRef = "denyPermissions", toolRefs = "referenceTools")
public interface BoundAdvisor {
    CompletableFuture<String> answer(@UserMessage String question);
}
```

```java
@Post(value = "/once", consumes = MediaType.TEXT_PLAIN, produces = MediaType.TEXT_PLAIN)
@ExecuteOn(TaskExecutors.BLOCKING)
public String once(@Body String question) {
    try (var instance = agents.create(BoundAdvisor.class)) {
        return instance.agent().answer(question).join();
    }
}
```

<a id="streaming"></a>

## Reactor streaming

Return `Flux<CopilotAgentEvent>` to stream native session events, for example as
server-sent events:

```java
@Post(value = "/{conversation}/stream", consumes = MediaType.TEXT_PLAIN, produces = MediaType.TEXT_EVENT_STREAM)
public Flux<String> stream(String conversation, @Body String question) {
    return streaming.stream(conversation, question)
            .map(CopilotAgentEvent::event)
            .filter(AssistantMessageEvent.class::isInstance)
            .map(event -> ((AssistantMessageEvent) event).getData().content());
}
```

Streams buffer up to `micronaut.copilot.streaming.buffer-capacity` (default `256`) events.

<a id="conditional-agents"></a>

## Conditional agents

`@Requires` on an agent interface makes the agent conditional, like any other bean.

<a id="instructions"></a>

## System instructions

Instructions can be inline (`instructions`), loaded from a class-path resource, or
supplied by a `SystemMessageConfig` bean named in `systemMessageRef`.

<a id="validation"></a>

## Startup validation

Agents and their bean references are validated at startup, so a missing tool bean
or an unknown MCP server fails the application instead of the first request.
