# copilot-core

Framework-neutral agent API and engine shared by the Java integrations in this
repository. Applications do not depend on it directly; it arrives transitively through
the [Spring Boot starter](../spring-boot/), the [Quarkus extension](../quarkus/), or the
[Micronaut integration](../micronaut/).

- `com.microsoft.copilot.integrations.core`: the public API (`@CopilotAgent`, `CopilotAgents`,
  `AgentConversation`, hooks, telemetry, and related types), plus `CopilotSessionStore` for
  listing, reading, and deleting the agents' persisted sessions.
- `com.microsoft.copilot.integrations.core.internal`: the shared engine (agent definitions,
  proxies, invocation, governance, tool/MCP/provider binding). Frameworks plug in through
  `BindingContext` and `BeanLookup`. `DefaultCopilotSessionStore` and
  `CopilotSessionCleaner` implement the session store and the opt-in age-based cleanup
  that each framework schedules. This package is not a supported API.

The module has no Maven wrapper of its own; each integration build includes it as a
reactor module (`<module>../core</module>`):

```
cd spring-boot && ./mvnw verify   # or: cd quarkus (or micronaut) && ./mvnw verify
```

## Fresh-session loops (Ralph loop)

`CopilotLoop` sends the same prompt repeatedly, each iteration in a **new** session. State
lives on disk (for example `IMPLEMENTATION_PLAN.md` in the agent's working directory), not in
model context, and builds or tests are the quality gate. `CopilotLoops` is injectable from
every integration (a Spring/Quarkus/Micronaut bean, and `copilotLoops` in the Camel registry). It
binds a fresh instance of an ID-less `@CopilotAgent` interface for every iteration:

```java
CopilotLoop loop = CopilotLoop.builder()
        .prompt(() -> readFile("PROMPT_build.md"))  // re-read every iteration
        .maxIterations(20)
        .iterationTimeout(Duration.ofMinutes(15))
        .check(previous -> new ProcessBuilder("./mvnw", "-q", "verify").inheritIO().start().waitFor() == 0)
        .stopWhen(it -> it.output().contains("ALL TASKS DONE"))
        .listener(it -> log.info("iteration {} took {}", it.number(), it.duration()))
        .build();

CopilotLoop.Result result = copilotLoops.run(loop, CodingAgent.class, CodingAgent::work);
```

The loop stops at the first of `stopWhen` (after a successful iteration), `check` returning
`false` (it runs only between iterations), a failed or timed-out iteration (unless
`continueOnFailure(true)`), cancellation (thread interrupt or `cancelWhen`), or
`maxIterations`. `Result.stopReason()` says which. `loop.run(step)` accepts any custom step that
opens its own fresh session.

- **No implicit approval.** Each iteration is an ordinary agent call governed by the agent's
  permission policy. Grant only what the job needs.
- **Sandbox it.** An autonomous loop edits files and runs commands; point the agent's working
  directory at a disposable checkout and run it in a container or VM.
- **Observe tool use** with the existing hooks and telemetry listeners (for example tool-start
  events), not inside the loop.
- **Schedulers.** The loop blocks the calling thread, so it fits a Spring Batch `Tasklet`, a
  Quarkus `@Scheduled` method, a Micronaut `@Scheduled` job, or a Camel route bean
  (`bean:copilotLoops`). With `iterationTimeout`, iterations run on a virtual thread and
  `ThreadLocal` context is not propagated.

## Error-recovery hooks

`CopilotErrorRecoveryHooks` is an opt-in `CopilotAgentHooks` implementation based on the Copilot SDK
cookbook's [error-recovery recipe](https://github.com/github/awesome-copilot/blob/main/cookbook/copilot-sdk/python/error-recovery-hooks.md).
It sorts tool failures by phrase lists (permission denied, shell error, tool failure), adds a
"keep going" note as `additionalContext`, and stops after a configurable number of consecutive
failures of the same tool. Build it with `CopilotErrorRecoveryHooks.builder()` and register it like any
hooks bean; Spring Boot also offers `copilot.error-recovery.enabled=true`. The Java SDK has no
error-occurred hook, so transient model/runtime errors cannot be retried from Java.
