# Getting started

## Prerequisites

Use Java 21 or later. The integration currently builds against Camel 4.23.
For live agent requests, install and authenticate the
[Copilot CLI](https://docs.github.com/en/copilot/how-tos/copilot-cli)
with an account that can use Copilot. The SDK starts the CLI at runtime.
The [scripted test runtime](testing.html) needs neither the CLI nor network access.

## Install from source

Artifacts are unreleased. From the repository root:

```sh
cd java/camel
./mvnw install
```

This installs `copilot-core`, `copilot-camel-test`, and `copilot-camel` into the local
Maven repository. Add the component and the Camel components used by your application:

```xml
<dependency>
  <groupId>com.microsoft</groupId>
  <artifactId>copilot-camel</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>org.apache.camel</groupId>
  <artifactId>camel-direct</artifactId>
  <version>4.23.0</version>
</dependency>
```

## Run a route

The component is discovered through Camel's component registry. A minimal application
can create a context, add a route, and send a request using a producer template:

```java
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;

public class SupportApplication {
    public static void main(String[] args) throws Exception {
        try (var context = new DefaultCamelContext()) {
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:ask")
                        .to("copilot:agent:support"
                            + "?instructions=Answer questions about Apache Camel briefly."
                            + "&model=gpt-5");
                }
            });
            context.start();
            try (var template = context.createProducerTemplate()) {
                String answer = template.requestBody(
                    "direct:ask", "How do I handle a failed exchange?", String.class);
                System.out.println(answer);
            }
        }
    }
}
```

The component uses a single `CopilotClient` registry bean if present, or creates its
own client. Stop the context to release component-owned resources; application-supplied
clients remain the application's responsibility.

## External configuration

Use Camel property placeholders for deployment-specific model and instruction choices:

```properties
support.model=gpt-5
support.instructions=Answer support questions briefly.
```

```java
from("direct:ask")
    .to("copilot:agent:support"
        + "?model={{support.model}}"
        + "&instructions={{support.instructions}}");
```

For large instructions, keep the prompt in a resource such as
`src/main/resources/prompts/support.md` and use
`instructionsResource=classpath:prompts/support.md` instead of inline instructions.
Do not put credentials in route URIs or checked-in properties.

Continue with [agents and conversations](agents.html) or expose a
[route as a tool](tools.html).
