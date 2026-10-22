---
title: Tools
---

# Tools

Tools are functions the model can call during a turn. The Copilot runtime runs the tool loop;
this integration converts your tools to SDK tools, validates their input, propagates the
request context into them, and turns failures into tool results instead of crashed turns.

**In this chapter**

- [Tool definitions](#tool-definitions)
- [Accepted tool inputs](#accepted-tool-inputs)
- [Results and failures](#results-and-failures)
- [NestJS tool providers](#nestjs-tool-providers)
- [Tool interceptors](#tool-interceptors)
- [Tool filters](#tool-filters)

## Tool definitions

```ts
import { z } from "zod";
import { defineCopilotTool } from "@copilot-sdk-integrations/core";

export const lookupOrder = defineCopilotTool({
  name: "lookupOrder",
  description: "Look up an order by id",
  input: z.object({ id: z.string().describe("The order id") }),
  async execute({ id }, { context, signal }) {
    return orders.find(id, context.principal, { signal });
  },
});
```

| Field | Purpose |
| --- | --- |
| `name` | `^[a-zA-Z0-9_-]+$`, at most 64 characters, unique per agent. |
| `description` | Shown to the model; write it for the model. |
| `input` | A Zod schema (preferred) or a raw JSON Schema object. Zod input is validated before `execute` runs. |
| `execute(input, invocation)` | The handler. `invocation` has `toolCallId`, `toolName`, `sessionId`, `signal`, and the [`context`](context-and-identity.md). |
| `skipPermission` | Skip the [permission policy](governance.md) for this tool. Use only for harmless, read-only tools. |
| `overridesBuiltInTool` | Replace a runtime built-in tool of the same name. |

`defineCopilotTool` validates the definition eagerly and infers the input type; a plain object
literal of the same shape works too.

## Accepted tool inputs

An agent's `tools` (and the per-call `tools` option) accept:

- a `CopilotToolDefinition`, as above;
- an SDK tool from `defineTool` in `@github/copilot-sdk`;
- a record of [AI SDK](https://ai-sdk.dev) `tool()` objects keyed by name.

```ts
import { tool } from "ai";

const weather = tool({
  description: "Current weather for a city",
  inputSchema: z.object({ city: z.string() }),
  needsApproval: true,
  execute: async ({ city }) => fetchWeather(city),
});

defineAgent({ name: "concierge", tools: [{ weather }], permissions: permissions.allowCustomTools() });
```

For AI SDK tools: `needsApproval` (a boolean or a function) turns an approval into an
[`ask-user`](governance.md#ask-user-approvals) decision; streaming tools (async generators)
report their last yielded value; tools without `execute` are declaration-only.

Duplicate tool names in one agent throw `CopilotConfigurationError`.

## Results and failures

| Handler returns | Sent to the model |
| --- | --- |
| a string | as is |
| `null` or `undefined` | an empty string |
| an SDK `ToolResultObject` | passed through |
| anything else | `JSON.stringify(value)` |

Handlers never crash a turn: invalid input and thrown errors become a tool result with
`resultType: "failure"`, so the model can recover. The run is visible as a `tool-end` event
with `success: false`.

## NestJS tool providers

In NestJS, tools are methods of providers decorated with `@CopilotTool`:

```ts
@Injectable()
export class OrdersTools {
  constructor(private readonly orders: OrdersService) {}

  @CopilotTool({ description: "Look up an order", input: z.object({ id: z.string() }) })
  lookupOrder({ id }: { id: string }, invocation: CopilotToolInvocation) {
    return this.orders.find(id, invocation.context.principal);
  }

  @CopilotTool({ name: "cancelOrder", description: "Cancel an order", input: z.object({ id: z.string() }) })
  cancel({ id }: { id: string }) {
    return this.orders.cancel(id);
  }
}
```

`@CopilotTool` accepts `name` (default: the method name), `description`, `input`,
`skipPermission`, and `overridesBuiltInTool`. Attach providers to agents with
`@CopilotAgent({ tools: [OrdersTools] })`, `@Tools(OrdersTools)` on a class or method, or
`CopilotModule.forFeature([Agent], { tools: [OrdersTools] })`. Core tool inputs can be mixed in.

- The provider must be registered in some module; a provider with no `@CopilotTool` methods
  fails at bootstrap.
- Singleton providers are resolved once. Request-scoped and transient providers are resolved
  once per turn, in a DI context whose request is `{ copilot: CopilotContext }`.

## Tool interceptors

Interceptors wrap tool calls, for example to audit or rate-limit them:

```ts
@Injectable()
export class AuditInterceptor implements CopilotToolInterceptor {
  constructor(private readonly audit: AuditService) {}

  async intercept(ctx: CopilotToolExecutionContext, next: () => Promise<unknown>) {
    const result = await next();
    await this.audit.record(ctx.invocation.context.principal, ctx.toolName, ctx.input);
    return result;
  }
}

@Injectable()
@UseToolInterceptors(AuditInterceptor)
export class OrdersTools { /* ... */ }
```

`@UseToolInterceptors(...)` works on classes and methods; class interceptors run first. The
context has `toolName`, `input`, `invocation`, `instance`, `methodName`, and `providerClass`.
Interceptor classes are resolved from DI when registered, otherwise instantiated with `new`.

## Tool filters

Filters decide which tools the runtime exposes, using the SDK's ToolSet syntax:

| Entry | Matches |
| --- | --- |
| `builtin:*`, `builtin:<name>` | Runtime built-in tools (shell, file edits, URL fetch, ...). |
| `custom:*`, `custom:<name>` | The agent's own tools. |
| `mcp:*`, `mcp:<server>` | MCP server tools. |
| `<name>` | A tool by exact name. |

| Definition | Effect |
| --- | --- |
| `builtInTools` unset or `false` (default) | `builtin:*` is appended to `excludedTools`, hiding every built-in tool. |
| `builtInTools: true` | Built-in tools follow the runtime defaults. |
| `availableTools` unset | `["custom:*", "mcp:*"]`, plus `builtin:*` with `builtInTools: true` (the client runs in the SDK's `empty` mode, which needs an explicit allow-list). |
| `availableTools: [...]` | Allow-list, copied as is. |
| `excludedTools: [...]` | Deny-list. |

Validation runs when the agent is defined and fails when:

- both `availableTools` and `excludedTools` are set;
- `availableTools` lists `builtin:` entries without `builtInTools: true`;
- `availableTools` hides every one of the agent's own tools, or every configured MCP server.

Filters only control visibility. Every visible tool call still goes through the
[permission policy](governance.md), except tools with `skipPermission`.
