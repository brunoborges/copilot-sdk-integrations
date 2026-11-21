# Getting started

## Prerequisites

Use Java 21 or later. The integration currently builds against Camel 4.23.
For live agent requests, install and authenticate the
[Copilot CLI](https://docs.github.com/en/copilot/how-tos/copilot-cli)
with an account that can use Copilot. The SDK starts the CLI at runtime.
The [scripted test runtime](testing.html) needs neither the CLI nor network access.

## Install

Releases are published to GitHub Packages, which requires authentication even
for public packages. Add the repository to your `pom.xml`:

```xml
<repositories>
  <repository>
    <id>copilot-sdk-integrations</id>
    <url>https://maven.pkg.github.com/brunoborges/copilot-sdk-integrations</url>
  </repository>
</repositories>
```

Add matching credentials to `~/.m2/settings.xml`, using a
[personal access token (classic)](https://github.com/settings/tokens/new?scopes=read:packages)
with the `read:packages` scope:

```xml
<settings>
  <servers>
    <server>
      <id>copilot-sdk-integrations</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

See [Installing released packages](https://github.com/brunoborges/copilot-sdk-integrations#installing-released-packages)
for Gradle and GitHub Actions setup. Add the component and the Camel components used
by your application:

```xml
<dependency>
  <groupId>com.microsoft</groupId>
  <artifactId>copilot-camel</artifactId>
  <version>0.1.0</version>
</dependency>
<dependency>
  <groupId>org.apache.camel</groupId>
  <artifactId>camel-direct</artifactId>
  <version>4.23.0</version>
</dependency>
```

## Install from source

To try unreleased changes, build from the repository root:

```sh
cd java/camel
./mvnw install
```

This installs `copilot-core`, `copilot-camel-test`, and `copilot-camel` into the local
Maven repository. Use version `0.1.0-SNAPSHOT` in the dependencies above.

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
own client. The component-owned client runs in `CopilotClientMode.CODING_AGENT` and
starts the Copilot CLI as a child process (stdio), or hosts the runtime in process when
`COPILOT_SDK_DEFAULT_CONNECTION=inprocess`. A client you supply must set its own mode. Stop the context to release component-owned resources; application-supplied
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
