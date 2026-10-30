---
title: Testing
---

<a id="testing"></a>

# Testing

Add the test module:

```xml
<dependency>
    <groupId>com.microsoft</groupId>
    <artifactId>copilot-micronaut-test</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

<a id="scripted-runtime"></a>

## Scripted in-memory runtime

While it is on the class path, the `CopilotClient` is replaced by a scripted,
in-memory runtime that never starts the CLI. It is reset after each
`@MicronautTest` method:

```java
@MicronautTest
class MicronautAdvisorTest {

    @Inject CopilotTestRuntime copilot;
    @Inject MicronautAdvisor advisor;

    @Test
    void answersWithTheReferenceTool() {
        copilot.agent("micronaut-advisor")
                .invokesTool("describe_term", Map.of("term", "micronaut"))
                .repliesUsing(turn -> "In short: " + turn.toolResult("describe_term"));

        assertTrue(advisor.answer("c-1", "What is Micronaut?").join().startsWith("In short: "));
        assertEquals(List.of("What is Micronaut?"), copilot.prompts("micronaut-advisor"));
    }
}
```

<a id="real-runtime"></a>

## Testing against a real runtime

Set `micronaut.copilot.test-runtime.enabled=false`, start a `CopilotContainer`
with an image that runs `copilot --server`, such as the shared
[Testcontainers runtime image](../testcontainers/), and return its `properties()`
from a `TestPropertyProvider`.
