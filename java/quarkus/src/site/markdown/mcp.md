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
in-process [CDI tool beans](tools.html). The extension maps local stdio
servers and remote HTTP/SSE servers to the SDK's native `McpStdioServerConfig`
and `McpHttpServerConfig` types. No additional MCP client dependency is needed.

<a id="define-servers-in-configuration"></a>

## Define servers in configuration

```ini
quarkus.copilot.mcp.servers.files.type=stdio
quarkus.copilot.mcp.servers.files.command=/usr/bin/node
quarkus.copilot.mcp.servers.files.args=/opt/mcp/files-server.js,/workspace/reference
quarkus.copilot.mcp.servers.files.working-directory=/workspace
quarkus.copilot.mcp.servers.files.env.SERVER_MODE=readonly
quarkus.copilot.mcp.servers.files.tools=read_file,list_directory
quarkus.copilot.mcp.servers.files.timeout=10s

quarkus.copilot.mcp.servers.documentation.type=http
quarkus.copilot.mcp.servers.documentation.url=https://mcp.example.com/mcp
quarkus.copilot.mcp.servers.documentation.headers.Authorization=Bearer ${DOCS_MCP_TOKEN}
quarkus.copilot.mcp.servers.documentation.tools=search
```

The executable/server must already be installed on the runtime host. The
extension does not download server packages or start processes during Quarkus
startup. Put credentials in environment variables or a Quarkus config source
backed by a secret store, not source control. List values are comma-separated;
escape a literal comma inside one element as `\,`. Quote map names containing
dots, for example `quarkus.copilot.mcp.servers."internal.docs".type=http`.

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
The same selection works on explicit-conversation-ID interfaces (which are
injectable with `@Inject`) and Mutiny streaming agents. Selected configurations
are passed on both session creation and resumption, before application
customizers run.

Deployment configuration can replace the annotation's selection:

```ini
quarkus.copilot.agents.researcher.mcp-servers=documentation
```

An omitted `mcp-servers` retains the annotation; `mcp-servers=none` selects no
extension-defined servers (Quarkus configuration cannot express an empty list).
Lists replace rather than merge. Missing, duplicate, blank, or ambiguous
references fail agent initialization during startup, before model work.
Annotation references support `${key}` and `${key:default}` placeholders
resolved from MicroProfile Config.

<a id="native-sdk-beans-and-sessions"></a>

## Native SDK beans and sessions

A reference can also name a native `com.github.copilot.rpc.McpServerConfig`
CDI bean. This keeps SDK-specific features, including OAuth configuration,
available without adding a second Quarkus-specific transport API. CDI beans
have no implicit name, so give the producer an explicit `@Named`:

```java
@Singleton
public class McpConfiguration {

    @Produces
    @Singleton
    @Named("enterpriseDocs")
    McpHttpServerConfig enterpriseDocs() {
        return new McpHttpServerConfig()
                .setUrl("https://mcp.example.com/mcp")
                .setTools(List.of("search"))
                .setTimeout(10_000);
    }
}
```

Select it with `@CopilotAgent(mcpServers = "enterpriseDocs")` or a per-agent
property. Do not define a property server and an SDK bean under the same
selected name; ambiguity is an error, not an implicit override. Native beans
retain their SDK settings and are the application's validation responsibility.
`@Named` beans are never removed by ArC as unused.

When building native sessions, inject the runtime configuration mapping and
convert it with `com.microsoft.copilot.integrations.quarkus.runtime.config.CopilotConfigs`:

```java
@Inject
CopilotRuntimeConfig config;

SessionConfig sessionConfig() {
    return new SessionConfig()
            .setMcpServers(CopilotConfigs.mcp(config).toServerConfigs())
            .setOnPermissionRequest(permissionHandler);
}
```

`CopilotConfigs.mcp(config)` returns a validated `CopilotMcpProperties`.
`toServerConfigs()` returns a map of all property definitions with fresh SDK
configuration objects and performs no I/O. For a subset, use
`CopilotConfigs.mcp(config).servers().get("files").toServerConfig()` under the
desired map key. Native SDK beans are not added to this property map
automatically. Applications own the lifecycle of native sessions.

For advanced session behavior, use `CopilotAgentCustomizer` on both its create
and resume overloads. Examples include SDK MCP authentication callbacks,
OAuth token storage, and `setDisabledMcpServers(...)` for runtime-provided
servers. Customizers run last and may replace `mcpServers`; treat shared
definition/bean values as immutable rather than modifying them in place.

<a id="security-and-lifecycle"></a>

## Security and lifecycle

- Property-defined `tools` defaults to an empty list: no tools exposed by that
  server. List allowed tool names explicitly; `tools=*` explicitly allows all.
  Native SDK beans follow their own SDK configuration.
- Selecting a server is not a permission grant or a sandbox. Existing
  permission-handler behavior is unchanged. Tool allowlists and runtime
  authorization are separate controls.
- Only trusted deployment configuration should supply executable paths,
  arguments, server URLs, headers, and environment values. Do not construct
  server definitions from untrusted prompts or request parameters.
- Connections and stdio processes are managed by the Copilot runtime, not the
  CDI container. With `quarkus.copilot.cli-url`, commands, working directories,
  and network access are evaluated on that external runtime host.
- MCP HTTP URLs are actual HTTP(S) endpoints, unlike the extension's raw-TCP
  `cli-url`. Prefer HTTPS for remote MCP endpoints. Configuration, including
  headers and environment secrets, is transmitted to the Copilot runtime;
  secure that connection too.
- An empty agent selection does not disable built-in or globally configured
  runtime servers. Use the SDK's explicit controls where isolation requires
  it. Connection and tool failures propagate through invocation futures or
  `Multi` failures; no replacement server is silently selected.

---

**Previous:** [CDI tool beans](tools.html)

**Next:** [BYOK providers](byok.html)
