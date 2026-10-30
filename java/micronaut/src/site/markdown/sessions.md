---
title: Sessions
---

<a id="sessions"></a>

# Sessions

Each conversation is persisted in a Copilot session. Inject `CopilotSessionStore`
to list, read, or delete them, for example to build a conversation history view:

```java
@Inject CopilotSessionStore sessions;

String id = sessions.sessionId(Advisor.class, conversationId);
sessions.messages(id).ifPresent(history -> history.forEach(m -> LOG.info(m.role() + ": " + m.content())));
sessions.delete(id);
```

`list()` returns only agent-created (`agent-…`) sessions, newest first. Deleting a
session waits for a turn in flight on it. Calls block, so run them on the blocking
executor (for example with `@ExecuteOn(TaskExecutors.BLOCKING)`); each waits at
most `micronaut.copilot.sessions.timeout` (default `2m`). The store is
`@Secondary`, so an application bean replaces it.

<a id="cleanup"></a>

## Cleanup

Old sessions are kept until deleted. To delete agent sessions not modified for a
while, set a max age:

| Property | Default | Effect |
| --- | --- | --- |
| `micronaut.copilot.sessions.cleanup.max-age` | unset (disabled) | Deletes agent sessions not modified for longer than this. |
| `micronaut.copilot.sessions.cleanup.interval` | half the max age, between `1m` and `1h` | Delay between sweeps. |

Cleanup runs on the `scheduled` task executor, never starts the client, only
deletes local `agent-` sessions, and skips sessions with a turn in progress.
