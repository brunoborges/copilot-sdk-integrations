---
title: Testing
---

<a id="testing"></a>

# Testing

**In this chapter**

- [Test module](#test-module)
- [Scripted runtime in Spring tests](#scripted-runtime-in-spring-tests)
- [Scripting replies](#scripting-replies)
- [Recorded interactions](#recorded-interactions)
- [Using the runtime without Spring](#using-the-runtime-without-spring)
- [Supported operations and limits](#supported-operations-and-limits)
- [Testcontainers and a real CLI server](#testcontainers-and-a-real-cli-server)
- [Connection details](#connection-details)

<a id="test-module"></a>

## Test module

`copilot-spring-boot-test` provides an in-memory, scripted Copilot runtime for
fast, deterministic tests that need no Copilot CLI, authentication, or network.
It also provides optional Testcontainers support for running against a real
headless CLI server.

```xml
<dependency>
    <groupId>com.microsoft</groupId>
    <artifactId>copilot-spring-boot-test</artifactId>
    <version>${copilot-spring-boot.version}</version>
    <scope>test</scope>
</dependency>
```

The module depends on `spring-test` and `mockito-core`, because `CopilotClient` and
`CopilotSession` are final SDK classes. Testcontainers dependencies are optional
and only needed for [`CopilotContainer`](#testcontainers-and-a-real-cli-server).

<a id="scripted-runtime-in-spring-tests"></a>

## Scripted runtime in Spring tests

Add `@AutoConfigureCopilotTestRuntime` to a `@SpringBootTest`, or to any test that
loads the Copilot auto-configuration. It does three things:

- It registers a `CopilotTestRuntime` bean and exposes its fake client as the
  `@Primary` `CopilotClient` bean. The regular client auto-configuration backs off,
  so no CLI process is started.
- It wraps each `CopilotAgentTelemetry` bean, so the runtime knows which
  `@CopilotAgent` sent each request.
- It resets scripts and recordings after every test method.

```java
@SpringBootTest
@AutoConfigureCopilotTestRuntime
class SpringAdvisorTests {

    @Autowired
    CopilotTestRuntime copilot;

    @Autowired
    SpringAdvisor advisor;

    @Test
    void answersWithTheReferenceTool() {
        copilot.agent("spring-advisor")
                .invokesTool("describe_term", Map.of("term", "spring boot"))
                .repliesUsing(turn -> "In short: " + turn.toolResult("describe_term"));

        String answer = advisor.answer("conversation-1", "What is Spring Boot?").join();

        assertThat(answer).startsWith("In short: Spring Boot configures");
        assertThat(copilot.prompts("spring-advisor")).containsExactly("What is Spring Boot?");
        assertThat(copilot.toolCalls("spring-advisor")).singleElement()
                .satisfies(call -> assertThat(call.succeeded()).isTrue());
    }
}
```

The example module's `SpringAdvisorTests` is a runnable version of this test.

<a id="scripting-replies"></a>

## Scripting replies

`copilot.agent(name)` returns the `CopilotAgentScript` for an agent name. The name is
the `@CopilotAgent` name. `copilot.anyAgent()` is the fallback script, used when the
agent has no turns of its own, and it also covers direct `CopilotClient` use. Each
terminal step ends one turn. Turns are consumed in order, and the last turn repeats
for any later prompt.

| Step | Kind | Behavior |
| --- | --- | --- |
| `replies(String)` | Terminal | Replies with fixed text. |
| `repliesWith(Function<String, String>)` | Terminal | Computes the reply from the prompt. |
| `repliesUsing(Function<CopilotTestTurn, String>)` | Terminal | Computes the reply from the agent, session, prompt, and tool calls of the current turn. |
| `replyJson(Object)` | Terminal | Serializes the value with Jackson. String-returning agents receive the JSON text, and typed `sendAndWait(..., Class, ...)` calls receive a deserialized object. |
| `streamsReply(String...)` | Terminal | Emits `assistant.message_delta` chunks, then replies with their concatenation. |
| `fails(Throwable)` | Terminal | Emits `session.error` and completes the request exceptionally with the throwable. |
| `invokesTool(name[, Map args])` | Preparatory | Calls the session's registered `ToolDefinition` handler, as the CLI would. |
| `emits(SessionEvent...)` | Preparatory | Dispatches native SDK events to session listeners. |
| `streams(String...)` | Preparatory | Emits message-delta events. |
| `delays(Duration)` | Preparatory | Waits before the next step, for example to exercise timeouts or cancellation. |
| `clear()` | — | Removes the agent's remaining turns. |

```java
copilot.agent("code-reviewer")
        .replies("First review.")                    // first prompt
        .delays(Duration.ofMillis(200))
        .streamsReply("Looks ", "good.");            // second and later prompts
copilot.agent("glossary").replyJson(new Definition("DI", "Dependency injection"));
copilot.agent("flaky").fails(new IllegalStateException("model unavailable"));
copilot.anyAgent().repliesWith(prompt -> "echo: " + prompt);
```

Every turn emits `user.message` first, then the scripted events, then
`assistant.message` and `session.idle`. As a result, `@CopilotAgent` listeners,
Reactor streaming, and observations see a realistic event sequence.
`CopilotTestEvents` has factories for common native events, such as
`messageDelta`, `assistantMessage`, `usage`, `toolStart`, `toolComplete`, `error`,
and `idle`. `event(type, data)` builds any other event type.

A scripted tool call looks up the tool by name, or by `namespace.name`, in the
session's current tools: tools set by `setTools`, otherwise the latest resume
configuration, otherwise the creation configuration. Spring `@CopilotTool` beans
therefore run through their proxies, as they do in production. If no registered
tool matches, the turn fails with a message that lists the registered tools. If
the handler throws, the exception is recorded on the call and the turn continues.

<a id="recorded-interactions"></a>

## Recorded interactions

| Accessor | Returns |
| --- | --- |
| `prompts()` / `prompts(agent)` | Prompt text in arrival order. |
| `receivedPrompts()` | `CopilotTestPrompt` records with the agent, session ID, `MessageOptions`, and requested response type. |
| `sessions()` / `sessions(agent)` / `session(id)` / `lastSession(agent)` | `CopilotTestSession` snapshots: creation `SessionConfig`, resume configurations, prompts, `resumed()`, `model()`, `tools()`, `toolNames()`, `systemMessage()`, and `mcpServers()`. |
| `sessionConfig(agent)` | The creation `SessionConfig` of the agent's latest session. |
| `toolCalls()` / `toolCalls(agent)` | `CopilotTestToolCall` records with the arguments, the result or failure, and `succeeded()`. |
| `aborts()` | IDs of the sessions on which `abort()` was called. |

Sessions are kept in memory. `getSessionMetadata(id)` returns metadata once a
session has been created, so the second call on a conversation resumes it:

```java
copilot.agent("spring-advisor").replies("Dependencies are injected.").replies("DI, summarized.");
advisor.answer("conversation-2", "Explain dependency injection.").join();
advisor.answer("conversation-2", "Summarize.").join();
assertThat(copilot.lastSession("spring-advisor").resumed()).isTrue();
```

All accessors return immutable snapshots. The runtime is thread-safe, so
concurrent conversations can be scripted and asserted.

<a id="using-the-runtime-without-spring"></a>

## Using the runtime without Spring

`new CopilotTestRuntime()` works in plain unit tests. Pass `runtime.client()` to the
code under test. When you build an agent invoker yourself, pass
`runtime.telemetry(delegate)` (or `runtime.telemetry()`) so the runtime can attribute
requests to agents. Alternatively, wrap direct client use in
`try (var scope = runtime.actAs("agent")) { ... }`. Call `reset()` to clear scripts,
sessions, and recordings.

<a id="supported-operations-and-limits"></a>

## Supported operations and limits

The fake client supports these `CopilotClient` operations: `start`, `stop`,
`forceStop`, `close`, `getState`, `createSession`, `resumeSession`,
`getSessionMetadata`, `listSessions`, `deleteSession`, and `getLastSessionId`.

The fake session supports `getSessionId`, both `on` overloads, `send`, every
`sendAndWait` overload (including typed responses and timeouts), `abort`,
`setTools`, `getMessages`, the event error handler setters, and `close`.

Any other SDK method throws `UnsupportedOperationException` with a clear message,
rather than returning `null`.

The scripted runtime does not consult permission handlers, run models, or
enforce MCP servers. Use the Testcontainers support to test against the real CLI
behavior.

<a id="testcontainers-and-a-real-cli-server"></a>

## Testcontainers and a real CLI server

`CopilotContainer` runs a headless Copilot CLI server in Docker. Add
`org.springframework.boot:spring-boot-testcontainers` and
`org.testcontainers:testcontainers-junit-jupiter`; Spring Boot manages their versions.
No public CLI server image is published, so you must supply an image name.

```java
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class CopilotServerTests {

    @Container
    @ServiceConnection
    static CopilotContainer copilot = new CopilotContainer("copilot-cli-server:local")
            .withGitHubToken(System.getenv("COPILOT_GITHUB_TOKEN"));

    @Autowired
    CopilotClient client;
}
```

`@ServiceConnection` publishes a `CopilotConnectionDetails` bean with the
container's `getCliUrl()` (`http://host:mappedPort`) and its
`getTcpConnectionToken()`. The token is a random UUID unless you call
`withConnectionToken(...)`.

The container passes these environment variables to the image:

- `COPILOT_PORT`: default `4321`; change it with `withCopilotPort(int)`.
- `COPILOT_CONNECTION_TOKEN`: the connection token.
- `COPILOT_GITHUB_TOKEN`: set only when you call `withGitHubToken(...)` with a
  non-null value.

The container is ready once the log contains `listening on port <n>`.

The following sample image installs the CLI with npm. The CLI server binds to
loopback only, so `socat` exposes it on all interfaces:

```dockerfile
FROM node:22-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends socat ca-certificates git \
 && rm -rf /var/lib/apt/lists/* \
 && npm install -g @github/copilot
COPY entrypoint.sh /usr/local/bin/copilot-server
RUN chmod +x /usr/local/bin/copilot-server
ENV COPILOT_PORT=4321
EXPOSE 4321
ENTRYPOINT ["copilot-server"]
```

```sh
#!/bin/sh
# entrypoint.sh
set -e
INTERNAL_PORT=$((COPILOT_PORT + 1))
socat TCP-LISTEN:"$COPILOT_PORT",fork,reuseaddr,bind=0.0.0.0 TCP:127.0.0.1:"$INTERNAL_PORT" &
exec copilot --server --port "$INTERNAL_PORT" --no-auto-update --log-level info "$@"
```

Build the image with `docker build -t copilot-cli-server:local .`. The CLI reads
`COPILOT_CONNECTION_TOKEN` itself.

The module's own container test runs only when `COPILOT_TEST_IMAGE` is set and
Docker is available:

```sh
COPILOT_TEST_IMAGE=copilot-cli-server:local ./mvnw -pl copilot-spring-boot-test -am verify
```

Without Docker or the variable, it is skipped.

Connecting and starting the client requires no GitHub token. Sending prompts
requires a token that is authorized for Copilot. Never commit tokens to source
control.

<a id="connection-details"></a>

## Connection details

`com.microsoft.copilot.integrations.springboot.autoconfigure.CopilotConnectionDetails` follows
Spring Boot's `ConnectionDetails` pattern. It has one method, `cliUrl()`, plus an
optional `tcpConnectionToken()`.

When a `CopilotConnectionDetails` bean exists, the auto-configured
`CopilotClientOptions` uses its URL and token instead of `copilot.cli-url` and
`copilot.tcp-connection-token`. It also ignores the local-process properties
`cli-path`, `cli-args`, and `working-directory`. All other `copilot.*` client
properties still apply.

Without such a bean, behavior is unchanged. You can also declare your own bean, for
example to discover a server at runtime.
