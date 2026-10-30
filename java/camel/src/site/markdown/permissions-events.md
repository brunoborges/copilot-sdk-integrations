# Permissions and events

## Deny by default

Endpoint agents reject permission requests unless a policy or handler allows them.
Built-in CLI tools such as shell and file tools are off unless `builtInTools=true`
or they are listed in `availableTools`. Enabling a tool and approving its execution
are separate decisions.

Use `allowAgentTools`, `allowTools`, `allowMcpServers`, `allowReadOnly`, or `allow`
only for operations the application intends to authorize. Keep policies narrow.
For decisions that require application context or a person, use a permission route.

## Human-in-the-loop approval

```java
from("copilot:permission:approvals?timeout=PT5M")
    .to("direct:askSlackAndWait");

from("direct:ask")
    .to("copilot:agent:ops"
        + "?toolRefs=restartService"
        + "&permissionHandlerRef=approvals");
```

The permission route receives a `PermissionRequest` body. Implement
`direct:askSlackAndWait` to obtain a decision and finish with Boolean `true` or an
approval string (`"approve"`, `"approved"`, `"allow"`, or `"true"`). A
`PermissionRequestResult` body is also accepted directly. Other results, exceptions,
and timeouts reject the request. The default timeout is 30 seconds; this example
extends it to five minutes. `restartService` must be a registered tool.

Permission exchanges carry `CopilotSessionId`, `CopilotToolCallId`, and
`CopilotPermissionKind`. Authenticate the approver and bind the decision to the
specific request; do not approve based only on model text or a conversation ID.

## Session events

```java
from("copilot:events:audit?types=assistant.*,tool.*")
    .to("bean:auditSink?method=record");

from("direct:ask")
    .to("copilot:agent:support?listenerRefs=audit");
```

The event endpoint registers a listener in the registry. Select it with
`listenerRefs`. The `types` option accepts comma-separated filters. Event exchanges
include `CopilotEventType`, `CopilotAgentName`, `CopilotConversationId`, and
`CopilotSessionId` where available.

Events are delivered in order on a dedicated thread. When more than `queueSize`
(default `1000`) events are pending, new events are dropped with a warning.
This is a bounded event sink, not a durable audit log. Keep consumers fast and
account for drops if your application needs complete records. Avoid logging
credentials or sensitive prompt/tool content.

## Error-recovery hooks

The shared core's `CopilotErrorRecoveryHooks` can tell the model to continue after
a failed tool call and stop after repeated consecutive failures of the same tool.
It is opt-in:

```java
context.getRegistry().bind("errorRecovery",
    CopilotErrorRecoveryHooks.builder().build());
```

```java
from("direct:ops").to("copilot:agent:ops?hookRefs=errorRecovery");
```

Configure `maxConsecutiveFailures` in the builder to bound repeated tool failures.
For a global hook, subclass it, annotate the subclass with
`@GlobalCopilotAgentHooks`, and bind that instance. These APIs are in
`com.microsoft.copilot.integrations.core`.

The Java SDK has no error-occurred hook, so this does not retry transient model or
runtime errors. Use Camel error handling around agent invocations when appropriate;
consider side effects before retrying an entire turn.
