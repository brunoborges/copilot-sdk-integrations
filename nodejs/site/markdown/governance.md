---
title: Tool governance and hooks
---

# Tool governance and hooks

Every tool call, MCP call, shell command, file write, and URL fetch the runtime wants to make
is a permission request. An agent's permission policy decides it. Hooks add finer control
around prompts and tool calls.

**In this chapter**

- [Deny by default](#deny-by-default)
- [Permission policies](#permission-policies)
- [Writing a policy](#writing-a-policy)
<!-- only:nestjs -->
- [Policies in NestJS](#policies-in-nestjs)
<!-- /only -->
- [Ask-user approvals](#ask-user-approvals)
- [Hooks](#hooks)
- [Hook composition](#hook-composition)

## Deny by default

- An agent without `permissions` denies every request.
- Built-in tools are hidden unless `builtInTools: true` (see [tool filters](tools.md#tool-filters)).
- A policy that throws, returns an unknown value, or ends in `abstain` denies.
- Built-in policies never auto-approve requests the runtime marks `managedApprovalRequired`.

## Permission policies

A policy is a function `(request, ctx) => decision`, sync or async. `ctx` has `agent` (the
definition), `customToolNames` (the agent's own tool names), and `context` (the
[`CopilotContext`](context-and-identity.md)). Decisions:

| Decision | Meaning |
| --- | --- |
| `approve` | Allow the request. |
| `deny` | Refuse it; the model sees a denied result. |
| `ask-user` | Route it to a human (see [below](#ask-user-approvals)). |
| `abstain` | No opinion; lets combinators consult the next policy. A final `abstain` denies. |

Ready-made policies on `permissions`:

| Policy | Approves |
| --- | --- |
| `permissions.denyAll()` | Nothing (the default). |
| `permissions.allowAll()` | Everything except `managedApprovalRequired` requests. **Dangerous** with built-in tools enabled. |
| `permissions.allowReadOnly()` | `read` requests and MCP requests flagged `readOnly`. |
| `permissions.allowCustomTools(names?)` | `custom-tool` requests for the agent's own tools, optionally narrowed to `names`. |
| `permissions.allowTools(names)` | `custom-tool` and `mcp` requests whose `toolName` is listed. |
| `permissions.allowMcpServers(names)` | `mcp` requests whose `serverName` is listed. |
| `permissions.allowKinds(kinds)` | Requests whose `kind` is listed. |
| `permissions.askUser(policy?)` | Turns everything the inner policy doesn't approve or deny into `ask-user`. |
| `permissions.anyOf(...policies)` | The first `approve` wins. Otherwise `ask-user` if any asked, `deny` if any denied, else `abstain`. |
| `permissions.allOf(...policies)` | Approves only if all approve. The first `deny` wins; otherwise `ask-user`, then `abstain`. |

Approval-only policies return `abstain` for requests they don't match, so they compose:

```ts
permissions: permissions.anyOf(
  permissions.allowReadOnly(),
  permissions.allowCustomTools(["lookupOrder"]),
  permissions.askUser(permissions.allowTools(["cancelOrder"])),
),
```

Request kinds are `shell`, `write`, `read`, `mcp`, `custom-tool`, `url`, `memory`, and `hook`.

## Writing a policy

```ts
import type { PermissionPolicy } from "@copilot-sdk-integrations/agent-core";

const tenantOnly: PermissionPolicy = (request, { context }) => {
  if (request.kind !== "custom-tool") return "abstain";
  return context.attributes.tenant === "acme" ? "approve" : "deny";
};

defineAgent({ name: "support", permissions: permissions.anyOf(tenantOnly, permissions.allowReadOnly()) });
```

`evaluatePermission(policy, request, ctx)` runs a policy with the fail-closed rules applied
and is useful in unit tests.

<!-- only:nestjs -->
## Policies in NestJS

`@CopilotAgent({ permissions })` accepts a `permissions.*` function, an instance with
`evaluate(request, ctx)`, or an injectable class implementing `CopilotPermissionPolicy`:

```ts
@Injectable()
export class OrdersPolicy implements CopilotPermissionPolicy {
  constructor(private readonly acl: AclService) {}

  async evaluate(request: PermissionRequest, ctx: PermissionPolicyContext): Promise<PermissionDecision> {
    if (request.kind !== "custom-tool") return "deny";
    return (await this.acl.canUseTools(ctx.context.principal)) ? "approve" : "deny";
  }
}

@CopilotAgent({ name: "support", tools: [OrdersTools], permissions: OrdersPolicy })
export abstract class SupportAgent { /* ... */ }
```

The class must be registered as a provider. An app-wide default for agents without their
own policy can be provided under the `COPILOT_PERMISSION_POLICY` token.
<!-- /only -->

## Ask-user approvals

When a policy returns `ask-user`, the turn waits for a human, up to `approvalWindow`
(default 120 s). If nobody answers, the request is denied as "user not available".

- **Streaming calls** emit a `permission-request` event with a `respond({ approved, feedback? })`
  function, then a `permission-resolved` event.
- **Non-streaming calls** use the `onPermissionRequest` send option; without it, the request is denied.

```ts
for await (const event of agent.stream({ prompt, conversationId })) {
  if (event.type === "permission-request") {
    event.respond({ approved: await askTheUser(event.request) });
  }
}
```

<!-- only:nestjs -->
In NestJS, answer a request from a different HTTP call with
`CopilotAgents.respondToPermission(conversationId, requestId, true | { approved, feedback }, { principal? })`.
Only the principal that started the turn can answer: the caller is `options.principal`, or the
current context's principal (set per request by the context interceptor). It returns `false`
when no such request is pending: already resolved, expired, or started by another
conversation or principal.
<!-- /only -->
<!-- only:fastify -->
In Fastify, `reply.copilotChat()` answers requests from a later `useChat` call through
`fastify.copilotApprovals`; only the principal that started the turn, in the same conversation,
can answer. See [useChat and approvals](fastify.md#usechat-and-approvals).
<!-- /only -->
With the AI SDK, approvals become `useChat` tool approvals; see
[Vercel AI SDK](ai-sdk.md#tool-approvals).

## Hooks

Hooks are the SDK's `SessionHooks`: `onPreToolUse`, `onPostToolUse`, `onPostToolUseFailure`,
`onPreMcpToolCall`, `onUserPromptSubmitted`, `onUserPromptTransformed`, `onSessionStart`,
`onSessionEnd`, `onErrorOccurred`, `onAgentStop`, `onSubagentStart`, and `onSubagentStop`.
Handlers run with the turn's `CopilotContext`.

```ts
defineAgent({
  name: "support",
  hooks: {
    onPreToolUse: async ({ toolName, toolArgs }) =>
      toolName === "cancelOrder" && !(await isBusinessHours())
        ? { permissionDecision: "deny", permissionDecisionReason: "Cancellations are closed" }
        : undefined,
  },
});
```

<!-- only:nestjs -->
Global hooks go in the client options (`createCopilot({ hooks })` or the module `hooks`).
In NestJS, provider methods can be hooks:

```ts
@Injectable()
export class PromptGuard {
  @CopilotHook("userPromptSubmitted", { agents: [SupportAgent] })
  check(input: { prompt: string }) {
    return containsSecrets(input.prompt) ? { decision: "block", reason: "Remove secrets first" } : undefined;
  }
}
```

The hook type is the SDK key without `on`, lower-camel-cased. `agents` (classes or names)
limits a hook to some agents; without it the hook applies to all.
<!-- /only -->
<!-- only:fastify -->
Global hooks go in the client options (`createCopilot({ hooks })`, or the plugin's
`client: { hooks }`).
<!-- /only -->

## Hook composition

<!-- only:nestjs -->
`composeHooks(...hooks)` merges hook sets. Global hooks run first, then NestJS-discovered hooks
(unscoped before agent-scoped), then the agent's own `hooks`. Handlers run sequentially:
<!-- /only -->
<!-- only:fastify -->
`composeHooks(...hooks)` merges hook sets. Global hooks run first, then the agent's own `hooks`.
Handlers run sequentially:
<!-- /only -->

| Hook | Composition |
| --- | --- |
| `onPreToolUse` | The first `deny` wins and stops the chain; otherwise `ask` beats `allow`. `modifiedArgs` are passed on. A throwing handler denies. |
| `onUserPromptSubmitted` | The first `block` wins and stops the chain; `modifiedPrompt` is passed on. A throwing handler blocks. |
| `onUserPromptTransformed` | `modifiedTransformedPrompt` is passed on; the last one wins. |
| `onPostToolUse` | `modifiedResult` is passed on; the last one wins. |
| `onPreMcpToolCall` | `metaToUse` is passed on; the last defined one wins. |
| `onAgentStop` | The first `block` wins. |
| `onSubagentStop` | The first `block` with a reason wins. |
| `onSessionStart` | `modifiedConfig` objects are shallow-merged in order. |
| `onSessionEnd` | `suppressOutput` is OR-ed; the last summary and cleanup actions win. |
| `onErrorOccurred` | The most conservative handling wins: `abort` over `skip` over `retry`. |

`additionalContext` strings from several handlers are joined, and `suppressOutput` is set if any
handler sets it. Handlers that return nothing have no opinion. Exceptions from handlers other
than the fail-closed ones above are swallowed.
