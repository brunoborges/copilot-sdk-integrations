# Testing and troubleshooting

## Scripted offline runtime

Add the Camel test helper with test scope:

```xml
<dependency>
  <groupId>com.microsoft</groupId>
  <artifactId>copilot-camel-test</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

`CopilotTestRuntime` provides a client and telemetry without starting the CLI or
making network requests. Bind them before starting the context, then script the
agent's next response:

```java
import com.microsoft.copilot.integrations.camel.test.CopilotTestRuntime;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

void answersWithoutNetwork() throws Exception {
    var runtime = new CopilotTestRuntime();
    try (var context = new DefaultCamelContext()) {
        context.getRegistry().bind("copilotClient", runtime.client());
        context.getRegistry().bind("copilotTelemetry", runtime.telemetry());
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:ask").to("copilot:agent:support");
            }
        });
        runtime.agent("support").replies("Use a dead letter channel.");
        context.start();
        try (var template = context.createProducerTemplate()) {
            assertEquals("Use a dead letter channel.",
                template.requestBody("direct:ask", "How do I handle failures?", String.class));
        }
        assertEquals(java.util.List.of("How do I handle failures?"),
            runtime.prompts("support"));
    }
}
```

The example assumes JUnit Jupiter and Camel Direct are test dependencies. Add
`@Test` when placing the method in a test class.

To exercise a tool route, register it in the same context and script a call before
the response:

```java
runtime.agent("support")
    .invokesTool("lookupOrder", Map.of("orderId", "42"))
    .replies("It shipped.");
```

The agent endpoint must select `toolRefs=lookupOrder` and have an explicit permission
policy such as `allowAgentTools=true`. This exercises the actual route, argument
conversion, and permission integration rather than replacing them with a model call.

## Build commands

From `java/camel`:

```sh
./mvnw verify
```

This builds the shared core, test runtime, and component and runs their tests.

To build the documentation, install the shared Java site skin first:

```sh
./mvnw -f ../quarkus/pom.xml -pl site-skin -am install
./mvnw -N site
```

Read `target/site/index.html` locally. The repository's Pages workflow publishes it
under `/camel/`, and Camel CI builds the documentation as well as the component.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Component is not found | Ensure `copilot-camel` and its Camel component service descriptor are on the runtime classpath. |
| CLI startup or authentication fails | Check that the CLI is installed and authenticated for the application's runtime user; use the scripted runtime for offline tests. |
| Prompt is rejected | The body or `prompt` expression must produce a non-empty string. |
| Agent-definition conflict | Configure a declarative agent on its interface, not through agent-definition endpoint options. |
| Tool is unavailable | Check that its consumer route is registered and its registry name appears in `toolRefs`. |
| Tool is denied | Tool selection does not grant permission; configure a narrow policy or permission route. |
| Event-drop warnings | Speed up the event sink or size its bounded queue for the workload; it is not durable delivery. |
| Unexpected conversation sharing | Scope conversation IDs to the authorized user/tenant and intended agent. |

Use Camel `onException`, redelivery, and circuit breakers to handle failed turns.
An entire-turn retry can repeat external tool side effects; design tools to be
idempotent or reconcile partial progress before retrying. Never fix a permission
failure by silently approving all operations.
