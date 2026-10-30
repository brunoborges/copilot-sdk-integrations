---
title: Example application
---

<a id="example"></a>

# Example application

The [`example`](https://github.com/brunoborges/copilot-sdk-integrations/tree/main/java/micronaut/example)
module is a Micronaut HTTP application with three agents:

| Agent | Interface | Endpoint |
| --- | --- | --- |
| `micronaut-advisor` | `MicronautAdvisor`, routed by `@ConversationId` | `POST /advisor/{conversation}` |
| `bound-advisor` | `BoundAdvisor`, a bound instance created per request | `POST /advisor/once` |
| `streaming-advisor` | `StreamingAdvisor`, returning `Flux<CopilotAgentEvent>` | `POST /advisor/{conversation}/stream` (server-sent events) |

The agents share a `ReferenceTools` tool bean, a `SystemMessageConfig` bean, and
a `DenyPermissions` handler that rejects every permission request. The
application enables the `copilot` management endpoint:

```properties
micronaut.application.name=copilot-micronaut-example
micronaut.copilot.agents.micronaut-advisor.response-timeout=PT2M
endpoints.health.details-visible=ANONYMOUS
endpoints.copilot.enabled=true
endpoints.copilot.sensitive=false
```

<a id="run"></a>

## Build and test

From `java/micronaut`:

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

The example's tests use the scripted runtime from `copilot-micronaut-test`, so they
need no Copilot CLI. GraalVM native images are not yet tested.
