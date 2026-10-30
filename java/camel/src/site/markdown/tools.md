# Routes as tools

Use a `copilot:tool:name` consumer to expose a route as an agent tool. It registers
a `ToolDefinition` bean under `name` in the Camel registry. An agent must select it
with `toolRefs`, and calls must also pass the agent's permission policy.

```java
from("copilot:tool:lookupOrder"
        + "?description=Look up an order by id"
        + "&parametersClass=com.acme.OrderQuery")
    .to("bean:orderService?method=lookup");

from("direct:ask")
    .to("copilot:agent:support"
        + "?instructions=Use lookupOrder to answer order questions."
        + "&toolRefs=lookupOrder"
        + "&allowAgentTools=true");
```

```java
package com.acme;

public record OrderQuery(String orderId) {}
```

Bind the `orderService` bean in the registry and include the Camel Bean component
for this example. Its `lookup(OrderQuery query)` method receives the converted
arguments; its return value becomes the tool result.

## Argument and result contract

Without `parametersClass`, arguments arrive as a `Map`. With it, the body is
converted to an instance of the specified class. For an explicit JSON Schema, use
`parametersSchema=classpath:schema.json`.

The final exchange body is returned to the model. A failed exchange fails the tool
call, so use ordinary Camel validation and exception handling to surface meaningful
errors. Do not silently convert failures into successful-looking tool results.

Validate all arguments and enforce authorization in the route or service, not in
model instructions. A model-generated order identifier is untrusted input.

## Tool access

`toolRefs` is a comma-separated list of registry bean names. Merely registering a
tool does not opt every agent into it. By default a selected tool still requires
permission; an agent can explicitly allow its application tools with
`allowAgentTools=true` or configure a narrower policy or permission handler.

A tool can set `skipPermission=true` to bypass permission checks for that tool.
Use that only when the tool is safe for every caller that can reach it. It is not
a replacement for tenant isolation or service-level authorization.

Tool exchanges include `CopilotSessionId`, `CopilotToolName`, and
`CopilotToolCallId` headers. See [configuration and headers](configuration.html)
and [permission routes](permissions-events.html).
