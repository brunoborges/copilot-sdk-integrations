---
title: System instructions
---

<a id="system-instructions"></a>

# System instructions

| SDK mode | Meaning | Configuration path |
| --- | --- | --- |
| `APPEND` | Retain the built-in system message and append content. | Inline instructions, resource, or SDK bean. |
| `REPLACE` | Replace the built-in system-message text. | Non-empty inline/resource content or SDK bean. |
| `CUSTOMIZE` | Override named sections while retaining others. Optional content is appended after sections. | SDK `SystemMessageConfig` bean. |

For resource-backed instructions:

```java
@CopilotAgent(
        name = "spring-advisor",
        instructionsResource = "classpath:/agents/advisor.txt",
        instructionsMode = SystemMessageMode.APPEND)
```

For section customization, define a bean inside a Spring configuration class:

```java
@Bean
SystemMessageConfig advisorInstructions() {
    return new SystemMessageConfig()
            .setMode(SystemMessageMode.CUSTOMIZE)
            .setSections(Map.of(
                    SystemMessageSections.TONE,
                    new SectionOverride()
                            .setAction(SectionOverrideAction.REPLACE)
                            .setContent("Be concise and use Java examples.")))
            .setContent("Focus on Spring Boot applications.");
}
```

Reference it with `@CopilotAgent(systemMessage = "advisorInstructions")`.
The SDK types in this example are `com.github.copilot.SystemMessageMode` and
`com.github.copilot.rpc.SystemMessageConfig`, `SystemMessageSections`,
`SectionOverride`, and `SectionOverrideAction`; `Map` is `java.util.Map`.

Section actions include replace, append, prepend, remove, preserve, and
transform. A transform can inspect the current section rather than reconstruct
it:

```java
new SectionOverride().setTransform(current ->
        CompletableFuture.completedFuture(current + "\nUse concise examples."));
```

Transform callbacks and configuration beans can be shared across conversations
and must be thread-safe. Use a session customizer for context-dependent
configuration. Instruction replacement is not a permission override or sandbox;
SDK configuration discovery/repository instructions are a separate concern.

---

**Previous:** [Conversations and lifecycle](conversations.html)

**Next:** [Spring tool beans](tools.html)
