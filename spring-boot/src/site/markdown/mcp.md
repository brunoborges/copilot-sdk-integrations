---
title: MCP servers
---

<a id="mcp-servers"></a>

# MCP servers

**In this chapter**

- [Define servers in configuration](#define-servers-in-configuration)
- [Select servers per agent](#select-servers-per-agent)
- [Native SDK beans and sessions](#native-sdk-beans-and-sessions)
- [Security and lifecycle](#security-and-lifecycle)

MCP servers provide tools through the Copilot runtime. They are distinct from
in-process [Spring tool beans](tools.html). The integration maps local stdio
servers and remote HTTP/SSE servers to the SDK's native `McpStdioServerConfig`
and `McpHttpServerConfig` types. No additional MCP client dependency is needed.

<a id="define-servers-in-configuration"></a>

## Define servers in configuration

```yaml
copilot:
  mcp:
    servers:
      files:
        type: stdio
        command: /usr/bin/node
        args:
          - /opt/mcp/files-server.js
          - /workspace/reference
        working-directory: /workspace
        env:
          SERVER_MODE: readonly
        tools:
          - read_file
          - list_directory
        timeout: 10s
      documentation:
        type: http
        url: https://mcp.example.com/mcp
        headers:
          Authorization: "Bearer ${MCP_DOCS_TOKEN}"
        tools:
          - search
```

The executable/server must already be installed on the runtime host. The
starter does not download server packages or start processes during Spring
initialization. Put credentials in environment/secret providers, not source
control. Use quoted bracket notation for map names containing dots, for example
`"[internal.docs]"`.

The [configuration reference](configuration.html#mcp-properties) lists every
field, default, validation rule, and the separate tool-call timeout.

<a id="select-servers-per-agent"></a>

## Select servers per agent

Definitions are not attached globally. Select only the servers an agent needs:

```java
@CopilotAgent(name = "researcher", mcpServers = {"files", "documentation"})
public interface Researcher {
    CompletableFuture<String> answer(@UserMessage String question);
}
```

Use the agent through `CopilotAgents.create(Researcher.class)` or `resume`.
The same selection works on explicit-conversation-ID interfaces and reactive
agents. Selected configurations are passed on both session creation and
resumption, before application customizers run.

Deployment configuration can replace the annotation's selection:

```yaml
copilot:
  agents:
    researcher:
      mcp-servers:
        - documentation
```

An omitted `mcp-servers` retains the annotation; `mcp-servers: []` selects no
integration-defined servers. Lists replace rather than merge. Missing,
duplicate, blank, or ambiguous references fail agent initialization, before
model work. Annotation references support Spring property placeholders.

<a id="native-sdk-beans-and-sessions"></a>

## Native SDK beans and sessions

A reference can also name a native `com.github.copilot.rpc.McpServerConfig`
bean. This keeps SDK-specific features, including OAuth configuration,
available without adding a second Spring-specific transport API:

```java
@Bean
McpHttpServerConfig enterpriseDocs() {
    return new McpHttpServerConfig()
            .setUrl("https://mcp.example.com/mcp")
            .setTools(List.of("search"))
            .setTimeout(10_000);
}
```

Select it with `@CopilotAgent(mcpServers = "enterpriseDocs")` or a per-agent
property. Do not define a property server and an SDK bean under the same
selected name; ambiguity is an error, not an implicit override. Native beans
retain their SDK settings and are the application's validation responsibility.

With starter auto-configuration active, inject
`io.github.brunoborges.copilot.autoconfigure.CopilotMcpProperties` when building
native sessions:

```java
var config = new SessionConfig()
        .setMcpServers(mcpProperties.toServerConfigs())
        .setOnPermissionRequest(permissionHandler);
```

`toServerConfigs()` returns a map of all property definitions with fresh SDK
configuration objects and performs no I/O. For a subset, use
`mcpProperties.servers().get("files").toServerConfig()` under the desired map
key. Native SDK beans are not added to this property map automatically.
Applications own the lifecycle of native sessions.

For advanced session behavior, use `CopilotAgentCustomizer` on both its create
and resume overloads. Examples include SDK MCP authentication callbacks,
OAuth token storage, and `setDisabledMcpServers(...)` for runtime-provided
servers. Customizers run last and may replace `mcpServers`; treat shared
definition/bean values as immutable rather than modifying them in place.

<a id="security-and-lifecycle"></a>

## Security and lifecycle

- Property-defined `tools` defaults to an empty list: no tools exposed by that
  server. List allowed tool names explicitly; `["*"]` explicitly allows all.
  Native SDK beans follow their own SDK configuration.
- Selecting a server is not a permission grant or a sandbox. Existing
  permission-handler behavior is unchanged. Tool allowlists and runtime
  authorization are separate controls.
- Only trusted deployment configuration should supply executable paths,
  arguments, server URLs, headers, and environment values. Do not construct
  server definitions from untrusted prompts or request parameters.
- Connections and stdio processes are managed by the Copilot runtime, not the
  Spring container. With `copilot.cli-url`, commands, working directories, and
  network access are evaluated on that external runtime host.
- MCP HTTP URLs are actual HTTP(S) endpoints, unlike the starter's raw-TCP
  `cli-url`. Prefer HTTPS for remote MCP endpoints. Configuration, including
  headers and environment secrets, is transmitted to the Copilot runtime;
  secure that connection too.
- An empty agent selection does not disable built-in or globally configured
  runtime servers. Use the SDK's explicit controls where isolation requires
  it. Connection and tool failures propagate through invocation futures or
  reactive errors; no replacement server is silently selected.

---

**Previous:** [Spring tool beans](tools.html)

**Next:** [BYOK providers](byok.html)
