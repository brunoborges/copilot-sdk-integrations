# Copilot for Apache Camel

`camel-copilot` is an [Apache Camel](https://camel.apache.org/) component that runs GitHub Copilot
agents from routes and exposes routes to agents as tools, permission handlers, and event sinks.
It is built on the [Copilot SDK for Java](https://github.com/github/copilot-sdk) and the shared
engine in [`java/core`](../core/), so agents behave exactly like the
[Spring Boot](../spring-boot/) and [Quarkus](../quarkus/) integrations. Design notes:
[DESIGN.md](DESIGN.md).

> Status: available, unreleased. Build it from this repository.

## Build

```sh
cd java/camel
./mvnw verify
```

Requires Java 21 and, at runtime, the Copilot CLI (the SDK starts it and authenticates with your
GitHub credentials).

## Dependency

```xml
<dependency>
  <groupId>io.github.brunoborges</groupId>
  <artifactId>camel-copilot</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Agents as route steps

```java
from("kafka:tickets")
    .setHeader("CopilotConversationId", simple("${header.ticketId}"))
    .to("copilot:agent:triage"
        + "?instructions=Classify the support ticket."
        + "&model=gpt-5"
        + "&outputClass=com.acme.Triage")
    .choice()
        .when(simple("${body.severity} == 'HIGH'")).to("jira:create")
        .otherwise().to("log:triage");
```

- The body is the prompt (or set `prompt=${body.text}`, a Simple expression).
- The reply body is the answer, or an `outputClass` instance for structured output.
- Exchanges with the same `CopilotConversationId` header (or `conversationId` expression) share a
  Copilot session. Without one, each exchange is a one-shot conversation whose session is deleted
  afterward.
- The producer is asynchronous; Camel's `onException`, redelivery, and circuit breakers apply.

Endpoint options mirror `@CopilotAgent` attributes: `instructions`, `instructionsResource`,
`instructionsMode`, `systemMessageRef`, `model`, `reasoningEffort`, `toolRefs`, `mcpServers`,
`provider`, `permissionHandlerRef`, `listenerRefs`, `customizerRefs`, `hookRefs`,
`tokenResolverRef`, `availableTools`, `excludedTools`, `excludedBuiltInAgents`, `builtInTools`,
`workingDirectory`, `additionalDirectories`. Lists are comma separated; `*Ref` values are bean
names in the Camel registry. Agent settings can also be set per endpoint: `responseTimeout`
(e.g. `PT2M`) and the permission shortcuts `allow`, `allowTools`, `allowMcpServers`,
`allowAgentTools`, `allowReadOnly`.

Built-in tools (shell, files) are **off** for endpoint agents unless `builtInTools=true` or they
are listed in `availableTools`.

## Routes as tools

```java
from("copilot:tool:lookupOrder"
        + "?description=Look up an order by id"
        + "&parametersClass=com.acme.OrderQuery")
    .to("sql:select status from orders where id = :#${body.id()}?outputType=SelectOne");

from("direct:ask")
    .to("copilot:agent:support?toolRefs=lookupOrder&allowAgentTools=true");
```

A tool endpoint registers a `ToolDefinition` bean named after the endpoint. Agents opt in with
`toolRefs`; calls still require permission unless the tool sets `skipPermission=true` or the agent
allows it. Arguments arrive as the body (a `Map`, or the `parametersClass` instance; a JSON Schema
can be given with `parametersSchema=classpath:schema.json`). The final body is the result; a
failed exchange fails the tool call. Treat arguments as untrusted input.

## Human-in-the-loop permissions

```java
from("copilot:permission:approvals?timeout=PT5M")
    .to("direct:askSlackAndWait");   // end with true / "approve" to approve

from("direct:ask")
    .to("copilot:agent:ops?toolRefs=restartService&permissionHandlerRef=approvals");
```

The body is the `PermissionRequest`. Anything other than an approval, an error, or a timeout
rejects the request. Without any handler or permission settings, requests are rejected.

## Session events

```java
from("copilot:events:audit?types=assistant.*,tool.*")
    .marshal().json()
    .to("kafka:copilot-audit");

from("direct:ask").to("copilot:agent:support?listenerRefs=audit");
```

Events are delivered in order on a dedicated thread; when more than `queueSize` (default 1000)
are pending, new events are dropped with a warning.

## Declarative agents

```java
@CopilotAgent(name = "advisor", instructions = "You are a Camel expert.")
public interface Advisor {
    String ask(@ConversationId String conversationId, @UserMessage String question);
}
```

```java
CopilotComponent copilot = context.getComponent("copilot", CopilotComponent.class);
copilot.setAgentTypes("com.acme.Advisor");

from("direct:advice").to("copilot:agent:advisor");
```

Declarative agents are configured on the interface only. Explicit-ID agents are also bound in the
registry under their name, and a `copilotAgents` bean creates and resumes the rest.

## Component options

| Option | Description |
| --- | --- |
| `client` | `CopilotClient` to use. Default: the single registry bean, else one owned by the component. |
| `telemetry` | `CopilotAgentTelemetry`. Default: the single registry bean, else none. |
| `agentSettings` | `CopilotAgentSettings`. Default: the single registry bean, else defaults. |
| `agentTypes` | Comma-separated `@CopilotAgent` interface names. |
| `maxConcurrentInvocations`, `maxOutstandingInvocations` | Execution limits shared by all agents. |
| `sessionTimeout` | Maximum wait for each session store or cleanup call. Default: `2m`. |
| `sessionMaxAge` | Deletes agent sessions not modified for longer than this. Default: unset (no cleanup). |
| `sessionCleanupInterval` | Delay between cleanup sweeps. Default: half of `sessionMaxAge`, between `1m` and `1h`. |

## Sessions

Each conversation is persisted in a Copilot session. The started component's `sessionStore()` (also bound in
the registry as `copilotSessionStore`) lists, reads, and deletes them:

```java
CopilotSessionStore sessions = context.getComponent("copilot", CopilotComponent.class).sessionStore();

String id = sessions.sessionId(Advisor.class, conversationId); // agents listed in agentTypes
sessions.messages(id).ifPresent(history -> history.forEach(m -> log.info(m.role() + ": " + m.content())));
sessions.delete(id);
```

`list()` returns only agent-created (`agent-…`) sessions, newest first, including those of `copilot:agent`
endpoints. Deleting a session waits for a turn in flight on it.

Set `sessionMaxAge` (for example `camel.component.copilot.session-max-age=30d`) to delete agent sessions not
modified for that long. Cleanup runs on a Camel-managed scheduled thread while the component is started, never
starts the client, and skips sessions with a turn in progress.

## Headers

| Header | Set on |
| --- | --- |
| `CopilotConversationId` | agent input and reply, events |
| `CopilotAgentName` | agent reply, events |
| `CopilotSessionId` | tool, permission, and event exchanges |
| `CopilotToolName`, `CopilotToolCallId` | tool exchanges (`CopilotToolCallId` also on permissions) |
| `CopilotPermissionKind` | permission exchanges |
| `CopilotEventType` | event exchanges |

## Testing

`camel-copilot-test` provides a scripted in-memory runtime, with no CLI or network:

```java
CopilotTestRuntime runtime = new CopilotTestRuntime();
context.getRegistry().bind("copilotClient", runtime.client());
context.getRegistry().bind("copilotTelemetry", runtime.telemetry());

runtime.agent("support").invokesTool("lookupOrder", Map.of("id", 42)).replies("It shipped.");
assertThat(template.requestBody("direct:ask", "Where is order 42?")).isEqualTo("It shipped.");
```
