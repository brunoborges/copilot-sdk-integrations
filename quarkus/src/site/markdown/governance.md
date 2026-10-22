---
title: Tool governance, permissions, and hooks
---

<a id="tool-governance-permissions-and-hooks"></a>

# Tool governance, permissions, and hooks

**In this chapter**

- [Restrict the tools an agent can see](#restrict-the-tools-an-agent-can-see)
- [Ready-made permission policies](#ready-made-permission-policies)
- [Property-driven permissions](#property-driven-permissions)
- [Session hooks as CDI beans](#session-hooks-as-cdi-beans)
- [Security notes](#security-notes)

Three independent layers control what an agent may do:

1. **Tool filtering** decides which tools the model can see at all.
2. **Permission handling** approves or rejects individual operations that the
   runtime asks permission for. Agents still deny every request by default.
3. **Hooks** observe or veto tool calls, prompts, and session lifecycle steps.

All three are applied to both newly created and resumed sessions, before
`CopilotAgentCustomizer`s run, so a customizer can still override them.

<a id="restrict-the-tools-an-agent-can-see"></a>

## Restrict the tools an agent can see

`@CopilotAgent` attributes and per-agent properties map directly onto the SDK
`SessionConfig`/`ResumeSessionConfig` settings of the same names:

| Annotation attribute | Property | SDK setting |
| --- | --- | --- |
| `availableTools` | `quarkus.copilot.agents.<name>.available-tools` | `availableTools` allow-list |
| `excludedTools` | `quarkus.copilot.agents.<name>.excluded-tools` | `excludedTools` deny-list |
| `builtInTools` | `quarkus.copilot.agents.<name>.built-in-tools` | `false` adds `builtin:*` to `excludedTools` |
| `excludedBuiltInAgents` | `quarkus.copilot.agents.<name>.excluded-built-in-agents` | `excludedBuiltInAgents` |
| `workingDirectory` | `quarkus.copilot.agents.<name>.working-directory` | `workingDirectory` for tool operations |
| `additionalDirectories` | `quarkus.copilot.agents.<name>.additional-directories` | `additionalDirectories` the session may access |

```java
@CopilotAgent(
        name = "support-agent",
        toolRefs = "orderTools",
        mcpServers = "docs",
        availableTools = {"view", "grep", "custom:*", "mcp:*"},
        workingDirectory = "${app.workspace}")
public interface SupportAgent {
    String answer(@ConversationId String id, @UserMessage String question);
}

@CopilotAgent(toolRefs = "orderTools", builtInTools = false)
public interface OrdersOnlyAgent {
    String answer(@ConversationId String id, @UserMessage String question);
}
```

Tool names follow the SDK `ToolSet` syntax. A bare name such as `view` matches
any tool with that name. A source-qualified pattern scopes the match:
`builtin:<name>`, `custom:<name>` (tools registered on the session, including
this agent's `@CopilotTool`/`ToolDefinition` tools), or `mcp:<name>` (MCP tools,
whose names are `<server>-<tool>`, for example `github-list_issues`). `*`
selects every tool from that source, as in `builtin:*`. Names may contain only
letters, digits, `_`, and `-`.

Semantics, as defined by the SDK and runtime:

- `availableTools` is an allow-list across **every** source: built-in, custom,
  and MCP tools. Tools not listed are hidden from the model.
- When `availableTools` is set, the runtime ignores `excludedTools`. The
  extension therefore rejects configurations that set both, rather than
  silently dropping the deny-list.
- The extension never widens an allow-list. Because the list also filters the
  agent's own tools, startup fails when an agent declares `toolRefs` but the list
  has no `custom:` entry, or selects `mcpServers` but the list has no `mcp:`
  entry. Add `custom:*`/`mcp:*`, or name specific tools such as `custom:echo`
  and `mcp:docs-search`, to state exactly which of them the model may use.
- `builtInTools = false` (or `built-in-tools=false`) hides every built-in tool
  by excluding `builtin:*`, leaving only the agent's custom and MCP tools. It
  cannot be combined with an allow-list: list the built-in tools you want
  instead.
- `excludedBuiltInAgents` hides runtime built-in sub-agents by name.

A property overrides the annotation value. Because Quarkus configuration cannot
express an empty list, the value `none` clears a list (`available-tools=none`
removes the annotation's allow-list). An empty `working-directory` is treated as
unset, so a property cannot restore the runtime default over an annotation
value; change the annotation instead. Annotation values support `${key}` and
`${key:default}` placeholders resolved from MicroProfile Config. Empty
annotation arrays mean "not set", which leaves the SDK/runtime defaults.

<a id="ready-made-permission-policies"></a>

## Ready-made permission policies

`CopilotPermissionPolicies` builds SDK `PermissionHandler`s you can expose as
`@Named` beans and select with `@CopilotAgent(permissionHandlerRef = "...")`:

| Factory | Approves |
| --- | --- |
| `denyAll()` | Nothing. |
| `allowAll()` | Everything; returns the SDK `PermissionHandler.APPROVE_ALL`. **Dangerous**: the agent can run shell commands, write files, and fetch URLs without review. Use only in throwaway sandboxes. |
| `allowKinds(kinds...)` | Requests whose kind is listed. |
| `allowTools(names...)` | `custom-tool` and `mcp` requests whose `toolName` is listed (MCP tools use the server's own tool name). |
| `allowCustomTools(tools)` | `custom-tool` requests for the given `ToolDefinition`s, matching `name` and `namespace.name`. |
| `allowMcpServers(names...)` | `mcp` requests whose `serverName` is listed. |
| `allowReadOnly()` | `read` requests and `mcp` requests flagged `readOnly`. |
| `anyOf(handlers...)` | The first approval from the handlers, consulted in order. Exceptions count as denial. |

Permission request kinds, available as constants:

| Constant | Kind | Typical source |
| --- | --- | --- |
| `SHELL` | `shell` | Shell command execution. |
| `WRITE` | `write` | File creation or modification. |
| `READ` | `read` | File reads. |
| `MCP` | `mcp` | MCP tool calls; carries `serverName`, `toolName`, `readOnly`. |
| `CUSTOM_TOOL` | `custom-tool` | Tools registered on the session; carries `toolName`. |
| `URL` | `url` | URL fetches. |
| `MEMORY` | `memory` | Memory storage. |
| `HOOK` | `hook` | Hook confirmations. |

```java
@Singleton
public class PermissionConfiguration {

    @Produces
    @Singleton
    @Named("supportPermissions")
    PermissionHandler supportPermissions() {
        return CopilotPermissionPolicies.anyOf(
                CopilotPermissionPolicies.allowReadOnly(),
                CopilotPermissionPolicies.allowMcpServers("docs"),
                CopilotPermissionPolicies.allowTools("lookup_order"));
    }
}
```

Every denial returns an SDK `reject` result and logs a warning naming the
request kind, as the default handler does. Requests marked
`managedApprovalRequired` by the runtime are never auto-approved by these
policies. `isApproval(result)` tells whether an SDK result approves a request,
which helps when writing your own composite handlers.

<a id="property-driven-permissions"></a>

## Property-driven permissions

When an agent has no `permissionHandlerRef` bean,
`quarkus.copilot.agents.<name>.permissions.*` builds a policy equivalent to
`anyOf` of the configured rules:

```ini
# request kinds
quarkus.copilot.agents.support-agent.permissions.allow=read,url
# custom-tool / MCP tool names
quarkus.copilot.agents.support-agent.permissions.allow-tools=lookup_order
# MCP server names
quarkus.copilot.agents.support-agent.permissions.allow-mcp-servers=docs
# this agent's own declared tools
quarkus.copilot.agents.support-agent.permissions.allow-agent-tools=true
# read requests and read-only MCP calls
quarkus.copilot.agents.support-agent.permissions.allow-read-only=true
```

`allow-agent-tools` approves exactly the custom tools declared by the agent's
`toolRefs` attribute. Configuring these properties together with a
`permissionHandlerRef` bean fails initialization. When nothing is configured, the
agent keeps the default handler that rejects every request.

<a id="session-hooks-as-cdi-beans"></a>

## Session hooks as CDI beans

Implement `CopilotAgentHooks` and override only the callbacks you need. Every
method receives the agent's `CopilotAgentContext` and the SDK hook input, and
returns the SDK hook output (or `null` for no opinion):

| Method | SDK hook |
| --- | --- |
| `onPreToolUse` | Before a tool runs; may allow, deny, ask, or modify arguments. |
| `onPostToolUse` | After a tool succeeds; may replace the result. |
| `onPostToolUseFailure` | After a tool fails. |
| `onUserPromptSubmitted` | Before a prompt is processed; may modify or block it. |
| `onSessionStart` / `onSessionEnd` | Session lifecycle. |
| `onAgentStop` | When the agent wants to stop; may block to continue. |
| `onSubagentStart` / `onSubagentStop` | Sub-agent lifecycle. |

```java
@ApplicationScoped
@Named("auditHooks")
public class AuditHooks implements CopilotAgentHooks {
    @Override
    public PreToolUseHookOutput onPreToolUse(CopilotAgentContext context, PreToolUseHookInput input) {
        if ("bash".equals(input.getToolName())) {
            return PreToolUseHookOutput.deny("Shell is not allowed for " + context.agentName());
        }
        return null;
    }
}

@CopilotAgent(hookRefs = "auditHooks")
public interface SupportAgent { /* ... */ }
```

A hooks bean can be attached to agents in three ways:

- `@GlobalCopilotAgentHooks` on the bean class or producer method applies it to
  every agent.
- `@CopilotAgentHooksFor(SupportAgent.class)` on the bean class or producer
  method applies it to the listed agent interfaces, so the agent annotation
  does not need to know the bean name.
- `hookRefs` on `@CopilotAgent` lists `@Named` bean names.

The class-level annotations are not bean-defining: the class still needs a
scope such as `@ApplicationScoped` or `@Singleton`. The extension records these
registrations at build time; annotations on producer fields are not detected.

```java
@Singleton
public class HookConfiguration {

    @Produces
    @Singleton
    @CopilotAgentHooksFor(SupportAgent.class)
    CopilotAgentHooks supportAudit() { /* ... */ }
}
```

Global hooks run first, then targeted hooks, each group ordered by `@Priority`
with **higher** values first (the ArC convention; Spring's `@Order` runs lower
values first), followed by `hookRefs` in declaration order. A bean selected
more than one way runs once. Hook selection is not configurable through
properties: bean names stay in code, where the compiler and IDE can check them.

When several hooks handle the same event, results combine as follows:

- **Pre-tool use**: the first `deny` wins and stops the chain; otherwise `ask`
  beats `allow`. Modified arguments are passed to later hooks and returned.
  A hook that throws produces `deny` (fail closed).
- **User prompt submitted**: the first `block` wins; a modified prompt is passed
  to later hooks. A hook that throws blocks the prompt (fail closed).
- **Post tool use**: a modified result is passed to later hooks.
- **Agent stop / sub-agent stop**: the first `block` wins.
- **Session end**: suppression flags are combined; the last summary/actions win.
- `additionalContext` values are joined with blank lines and `suppressOutput`
  is set if any hook sets it.
- Exceptions from the other hooks are logged and ignored.

Hooks run inside the agent's lifetime scope, like permission handlers. Hooks
that are not modelled here, such as the pre-MCP-tool-call hook, remain
available through a `CopilotAgentCustomizer` that sets SDK `SessionHooks`
directly; note that doing so replaces the hooks set by this extension.

<a id="security-notes"></a>

## Security notes

- Tool filtering, permission policies, and hooks reduce what an agent can do,
  but they are not an operating-system sandbox. Run agents with only the file
  system and network access they need.
- Prefer allow-lists and narrow policies to `allowAll()`.
- `workingDirectory` and `additionalDirectories` scope runtime file tools; they
  do not confine your own CDI tool beans.
- Hooks and permission handlers run on SDK threads. The caller's CDI request
  context, including the `SecurityIdentity`, is reactivated around them while
  it is still valid (see [Per-user authentication](authentication.html#caller-context-in-tools-and-handlers));
  otherwise resolve trusted identity explicitly rather than relying on
  request-scoped state.

---

**Previous:** [Events and customizers](events.html)

**Next:** [Configuration reference](configuration.html)
