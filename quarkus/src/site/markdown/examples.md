# Example application

Clone the repository and switch to the Quarkus project:

```sh
git clone https://github.com/brunoborges/copilot-sdk-integrations.git
cd copilot-sdk-integrations/quarkus
```

Install the extension modules locally (no artifacts are published yet). This also
builds the example and runs its tests against the scripted test runtime, so it
needs no Copilot CLI:

```sh
./mvnw --batch-mode --no-transfer-progress install
```

The example in `quarkus/example` is a Quarkus REST application with three agents
and one resource:

| Agent | Shape | Used by |
| --- | --- | --- |
| `QuarkusAdvisor` (`quarkus-advisor`) | Routed: `CompletableFuture<String> answer(@ConversationId, @UserMessage)` | `POST /advisor/{conversation}` |
| `BoundAdvisor` (`bound-advisor`) | Bound (no `@ConversationId`), created through `CopilotAgents.create` and closed per request | `POST /advisor/once` |
| `StreamingAdvisor` (`streaming-advisor`) | Routed `Multi<CopilotAgentEvent>` | `POST /advisor/{conversation}/stream` (server-sent events) |

Run it in dev mode against an installed, authenticated Copilot CLI (or set
`quarkus.copilot.cli-url` for an existing TCP server):

```sh
./mvnw -pl example quarkus:dev -Dquarkus.copilot.cli-path=/absolute/path/to/copilot
```

Then send plain-text questions:

```sh
curl -H 'Content-Type: text/plain' -d 'What is dependency injection?' localhost:8080/advisor/c-1
curl -H 'Content-Type: text/plain' -d 'Summarize that.' localhost:8080/advisor/c-1
curl -H 'Content-Type: text/plain' -d 'What are virtual threads?' localhost:8080/advisor/once
curl -N -H 'Content-Type: text/plain' -d 'What is Quarkus?' localhost:8080/advisor/c-2/stream
```

Repeating a conversation ID (`c-1`) resumes the same Copilot session. The bound
endpoint creates a fresh instance, answers once, and closes it. The stream
endpoint filters the event `Multi` down to final assistant messages.

All three agents share `ReferenceTools` (a `@Named` CDI bean exposing the
`describe_term` tool), explicit `DenyPermissions`, the `ConversationOutput`
listener that logs final assistant text with its conversation and session IDs,
and a customized `SystemMessageConfig` produced as `@Named("advisorInstructions")`
in `AgentConfiguration`. `application.properties` raises the advisor's response
timeout and enables the [diagnostics endpoint](management.html#copilot-endpoint);
with `quarkus-smallrye-health` on the class path, the `copilot` readiness check is
also active:

```sh
curl localhost:8080/q/health/ready
curl localhost:8080/q/copilot/agents
```

The example does not include Micrometer or `quarkus-info`; add them to try
[metrics](observability.html) and the [info section](management.html#info-contribution).

`QuarkusAdvisorTest` and `AdvisorResourceTest` show how to test the same agents
with the [scripted runtime](testing.html), including tool calls, resumed
conversations, bound instances, and streaming over HTTP.

Request counts describe application prompt submissions, not a guaranteed number
of underlying model calls: the runtime can perform additional model/tool
iterations. Running the application against a real CLI consumes real Copilot
usage.

---

**Previous:** [Testing](testing.html)

**Next:** [Security and troubleshooting](operations.html)
