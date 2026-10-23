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

Prompts carry a provenance label (`messageSource`: `user`, `system`, or `agent-<id>`). When the
endpoint sets none and no `CopilotMessageSources` scope is active, exchanges from `timer:`,
`scheduler:`, `quartz:`, or `cron:` routes are sent as `system`; other routes leave it unset
(runtime default `user`). The label is caller-supplied provenance, not an authenticated identity:
never authorize from it.

Endpoint options mirror `@CopilotAgent` attributes: `instructions`, `instructionsResource`,
`instructionsMode`, `systemMessageRef`, `model`, `reasoningEffort`, `toolRefs`, `mcpServers`,
`provider`, `permissionHandlerRef`, `listenerRefs`, `customizerRefs`, `hookRefs`,
`tokenResolverRef`, `availableTools`, `excludedTools`, `excludedBuiltInAgents`, `builtInTools`,
`toolSearch`, `toolSearchDeferThreshold`, `workingDirectory`, `additionalDirectories`, `messageSource`. Lists are comma separated; `*Ref` values are bean
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
