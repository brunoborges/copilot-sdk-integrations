# Testing

**In this chapter**

- [Test module](#test-module)
- [Scripted runtime in Quarkus tests](#scripted-runtime-in-quarkus-tests)
- [Scripting replies](#scripting-replies)
- [Recorded interactions](#recorded-interactions)
- [Using the runtime without Quarkus](#using-the-runtime-without-quarkus)
- [Supported operations and limits](#supported-operations-and-limits)
- [Testcontainers and a real CLI server](#testcontainers-and-a-real-cli-server)

<a id="test-module"></a>

## Test module

`copilot-quarkus-test` provides an in-memory, scripted Copilot runtime for
fast, deterministic tests that need no Copilot CLI, authentication, or network.
It also provides optional Testcontainers support for running against a real
headless CLI server.

```xml
<dependency>
    <groupId>com.microsoft</groupId>
    <artifactId>copilot-quarkus-test</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

The module depends on `mockito-core`, because `CopilotClient` and
`CopilotSession` are final SDK classes. `quarkus-junit5` and
`org.testcontainers:testcontainers` are optional dependencies: add
`quarkus-junit5` for `@QuarkusTest` (you normally have it already) and
Testcontainers only for [`CopilotContainer`](#testcontainers-and-a-real-cli-server).
The Quarkus BOM manages both versions.

<a id="scripted-runtime-in-quarkus-tests"></a>

## Scripted runtime in Quarkus tests

There is no annotation to add. While `copilot-quarkus-test` is on the test class
path, every `@QuarkusTest` gets the scripted runtime automatically:

- `CopilotTestRuntimeProducer` produces a `CopilotTestRuntime` bean and exposes
  its fake client as the `CopilotClient` bean. The extension's own client is a
  `@DefaultBean`, so it backs off and no CLI process is started.
- It contributes a `CopilotAgentTelemetryDecorator`, so the runtime knows which
  `@CopilotAgent` sent each request.
- `CopilotTestRuntimeResetCallback` resets scripts and recordings after every
  `@QuarkusTest` method.

Set the build-time property `quarkus.copilot.test-runtime.enabled=false` to turn
all of this off, for example to test against a
[real CLI server](#testcontainers-and-a-real-cli-server).

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.microsoft.copilot.integrations.quarkus.test.CopilotTestRuntime;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class QuarkusAdvisorTest {

    @Inject
    CopilotTestRuntime copilot;

    @Inject
    QuarkusAdvisor advisor;

    @Test
    void answersWithTheReferenceTool() {
        copilot.agent("quarkus-advisor")
                .invokesTool("describe_term", Map.of("term", "quarkus"))
                .repliesUsing(turn -> "In short: " + turn.toolResult("describe_term"));

        String answer = advisor.answer("conversation-1", "What is Quarkus?").join();

        assertTrue(answer.startsWith("In short: Quarkus moves framework work"));
        assertEquals(List.of("What is Quarkus?"), copilot.prompts("quarkus-advisor"));
        assertEquals(1, copilot.toolCalls("quarkus-advisor").size());
        assertTrue(copilot.toolCalls("quarkus-advisor").get(0).succeeded());
    }
}
```

The example module's `QuarkusAdvisorTest` is a runnable version of this test,
and `AdvisorResourceTest` drives the same agents over HTTP with REST Assured.

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
[Mutiny streams](streaming.html), and metrics see a realistic event sequence.
`CopilotTestEvents` has factories for common native events, such as
`messageDelta`, `assistantMessage`, `userMessage`, `usage`, `toolStart`,
`toolComplete`, `error`, and `idle`. `event(type, data)` builds any other event
type.

A scripted tool call looks up the tool by name, or by `namespace.name`, in the
session's current tools: tools set by `setTools`, otherwise the latest resume
configuration, otherwise the creation configuration. CDI `@CopilotTool` beans
therefore run through their client proxies, with interceptors, as they do in
production. If no registered tool matches, the turn fails with a message that
lists the registered tools. If the handler throws, the exception is recorded on
the call and the turn continues.

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
copilot.agent("quarkus-advisor").replies("Dependencies are injected.").replies("DI, summarized.");
advisor.answer("conversation-2", "Explain dependency injection.").join();
advisor.answer("conversation-2", "Summarize.").join();
assertTrue(copilot.lastSession("quarkus-advisor").resumed());
```

All accessors return immutable snapshots. The runtime is thread-safe, so
concurrent conversations can be scripted and asserted.

<a id="using-the-runtime-without-quarkus"></a>

## Using the runtime without Quarkus

`new CopilotTestRuntime()` works in plain unit tests. Pass `runtime.client()` to the
code under test. When you build an agent invoker yourself, pass
`runtime.telemetry(delegate)` (or `runtime.telemetry()`) so the runtime can attribute
requests to agents. Alternatively, wrap direct client use in
`try (var scope = runtime.actAs("agent")) { ... }`. Call `reset()` to clear scripts,
sessions, and recordings.

<a id="supported-operations-and-limits"></a>

## Supported operations and limits

The fake client supports these `CopilotClient` operations: `start`, `stop`,
`forceStop`, `close`, `getState` (always `CONNECTED`), `createSession`,
`resumeSession`, `getSessionMetadata`, `listSessions`, `deleteSession`, and
`getLastSessionId`.

The fake session supports `getSessionId`, both `on` overloads, `send`, every
`sendAndWait` overload (including typed responses and timeouts), `abort`,
`setTools`, `getMessages`, the event error handler setters, and `close`.

Any other SDK method throws `UnsupportedOperationException` with a clear message,
rather than returning `null`. In particular `getStatus`, `getAuthStatus`, and
`listModels` are unsupported, so the [`copilot` readiness check](management.html#readiness-check)
reports `DOWN` and `/q/copilot/models` reports a `reason` under the scripted
runtime. Assert that the check is registered rather than that it is `UP`.

The scripted runtime does not consult permission handlers, run models, or
enforce MCP servers. Use the Testcontainers support to test against the real CLI
behavior.

<a id="testcontainers-and-a-real-cli-server"></a>

## Testcontainers and a real CLI server

`CopilotContainer` runs a headless Copilot CLI server in Docker, and
`CopilotContainerTestResource` is a `QuarkusTestResourceLifecycleManager` that
starts it and points the extension at it by setting `quarkus.copilot.cli-url`
and `quarkus.copilot.tcp-connection-token`. Add `org.testcontainers:testcontainers`
to your test dependencies. No public CLI server image is published, so you must
supply an image name.

```java
import jakarta.inject.Inject;

import com.github.copilot.CopilotClient;

import com.microsoft.copilot.integrations.quarkus.test.testcontainers.CopilotContainerTestResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@WithTestResource(value = CopilotContainerTestResource.class,
        initArgs = @ResourceArg(name = "image", value = "copilot-cli-server:local"))
class CopilotServerIT {

    @Inject
    CopilotClient client;
}
```

Disable the scripted runtime for these tests, or it replaces the client the
resource configured. Because `quarkus.copilot.test-runtime.enabled` is a
build-time property, set it in `src/test/resources/application.properties` when
the whole test suite talks to the container, or in a `QuarkusTestProfile`'s
`getConfigOverrides()` for selected tests:

```ini
quarkus.copilot.test-runtime.enabled=false
```

The resource reads two init arguments, each with an environment variable
fallback:

| Init argument | Environment variable | Purpose |
| --- | --- | --- |
| `image` | `COPILOT_TEST_IMAGE` | Image to run. Required; the resource fails to start without it. |
| `github-token` | `COPILOT_GITHUB_TOKEN` | GitHub token passed to the container. Optional. |

The token is a random UUID unless you build the container yourself and call
`withConnectionToken(...)`. `CopilotContainer` passes these environment variables
to the image:

- `COPILOT_PORT`: default `4321`; change it with `withCopilotPort(int)`.
- `COPILOT_CONNECTION_TOKEN`: the connection token.
- `COPILOT_GITHUB_TOKEN`: set only when you call `withGitHubToken(...)` with a
  non-null value.

The container is ready once the log contains `listening on port <n>`. Its
`getCliUrl()` (`http://host:mappedPort`) and `getTcpConnectionToken()` are the
values the resource publishes; use them directly if you write your own
lifecycle manager.

The following sample image installs the CLI with npm. The CLI server binds to
loopback only, so `socat` exposes it on all interfaces:

```text
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

Connecting and starting the client requires no GitHub token. Sending prompts
requires a token that is authorized for Copilot. Never commit tokens to source
control.

There is no `@ServiceConnection`/`ConnectionDetails` equivalent: Quarkus test
resources publish configuration properties instead, so the regular
`quarkus.copilot.cli-url` and `quarkus.copilot.tcp-connection-token` handling
applies. Unlike Spring's connection details, the published `cli-url` does not
silently override local-process settings: client creation fails if the test
configuration also sets `quarkus.copilot.cli-path`, `cli-args`, or
`working-directory`, so leave those unset for container tests.

---

**Previous:** [Extension SPIs](extensions.html)

**Next:** [Example application](examples.html)
