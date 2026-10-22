package io.github.brunoborges.copilot.quarkus.runtime.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Runtime configuration of the GitHub Copilot extension. */
@ConfigMapping(prefix = "quarkus.copilot")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface CopilotRuntimeConfig {

    /** Whether to connect to the Copilot runtime during application startup instead of on first use. */
    @WithDefault("false")
    boolean eagerStart();

    /** Maximum wait for an eager connection. */
    @WithDefault("30s")
    Duration startupTimeout();

    /** Explicit executable path for a managed local Copilot CLI runtime. */
    Optional<String> cliPath();

    /** Extra arguments for a managed local Copilot CLI runtime. */
    Optional<List<String>> cliArgs();

    /** Working directory for a managed local Copilot CLI runtime. */
    Optional<String> workingDirectory();

    /** Runtime log level; omitted values retain the SDK default. */
    Optional<String> logLevel();

    /** Address of an existing Copilot server (port, host:port, or HTTP-style URI) instead of a managed runtime. */
    Optional<String> cliUrl();

    /** Authentication token for an existing TCP server; requires {@code cli-url}. */
    Optional<String> tcpConnectionToken();

    /** Shared invocation limits. */
    Execution execution();

    /** Per-agent overrides keyed by agent name. */
    @ConfigDocMapKey("agent-name")
    Map<String, Agent> agents();

    /** Named MCP servers. */
    Mcp mcp();

    /** Named BYOK model providers. */
    @ConfigDocMapKey("provider-name")
    Map<String, Provider> providers();

    /** Readiness health check settings. */
    Health health();

    /** Management endpoint settings. */
    Endpoint endpoint();

    /** Security integration settings. */
    Security security();

    /** Streaming settings. */
    Streaming streaming();

    /** Invocation limits. */
    interface Execution {
        /** Maximum invocations executing SDK work at once across all agents. */
        @WithDefault("8")
        int maxConcurrentInvocations();

        /** Maximum accepted invocations, including running and queued work. */
        @WithDefault("64")
        int maxOutstandingInvocations();
    }

    /** Agent overrides. */
    interface Agent {
        /** Model override; omitted values retain the annotation or runtime choice. */
        Optional<String> model();

        /** Model-specific reasoning effort override. */
        Optional<String> reasoningEffort();

        /** Maximum response wait; synchronous agent methods wait at most this long in total. */
        @WithDefault("60s")
        Duration responseTimeout();

        /** MCP server names replacing the annotation selection. */
        Optional<List<String>> mcpServers();

        /** BYOK provider name replacing the annotation selection; {@code none} selects no provider. */
        Optional<String> provider();

        /** Runtime tool allow-list replacing the annotation; {@code none} removes the allow-list. */
        Optional<List<String>> availableTools();

        /** Runtime tool deny-list replacing the annotation. */
        Optional<List<String>> excludedTools();

        /** Built-in subagent names hidden from the session. */
        Optional<List<String>> excludedBuiltInAgents();

        /** Whether runtime built-in tools are available; {@code false} excludes every built-in tool. */
        Optional<Boolean> builtInTools();

        /** Session working directory replacing the annotation. */
        Optional<String> workingDirectory();

        /** Directories accessible beyond the working directory. */
        Optional<List<String>> additionalDirectories();

        /** Declarative permission policy used when the agent has no permission handler bean. */
        Permissions permissions();
    }

    /** Declarative permission policy; a request is approved when any rule matches. */
    interface Permissions {
        /** Permission request kinds approved unconditionally, such as {@code read} or {@code url}. */
        Optional<List<String>> allow();

        /** Custom-tool or MCP tool names approved. */
        Optional<List<String>> allowTools();

        /** MCP server names whose tool calls are approved. */
        Optional<List<String>> allowMcpServers();

        /** Whether calls to the agent's own registered tools are approved. */
        @WithDefault("false")
        boolean allowAgentTools();

        /** Whether read requests and MCP tools declared read-only are approved. */
        @WithDefault("false")
        boolean allowReadOnly();
    }

    /** MCP configuration. */
    interface Mcp {
        /** MCP servers keyed by name. */
        @ConfigDocMapKey("server-name")
        Map<String, McpServer> servers();
    }

    /** A local (stdio) or remote (http) MCP server. */
    interface McpServer {
        /** Transport type. */
        @WithDefault("stdio")
        CopilotMcpProperties.Transport type();

        /** Command of a stdio server. */
        Optional<String> command();

        /** Arguments of a stdio server. */
        Optional<List<String>> args();

        /** Environment variables of a stdio server. */
        Map<String, String> env();

        /** Working directory of a stdio server. */
        Optional<String> workingDirectory();

        /** URL of an http server. */
        Optional<String> url();

        /** Request headers of an http server. */
        Map<String, String> headers();

        /** Exposed tool names; empty exposes none, {@code *} exposes all. */
        Optional<List<String>> tools();

        /** Tool-call timeout. */
        Optional<Duration> timeout();
    }

    /** A BYOK model provider. At most one of api-key, api-key-command, and bearer-token may be set. */
    interface Provider {
        /** Provider type. */
        @WithDefault("openai")
        CopilotProviderProperties.Type type();

        /** Absolute HTTP(S) endpoint URL. */
        String baseUrl();

        /** Static API key. */
        Optional<String> apiKey();

        /** Command run before each provider request to obtain an API key. */
        Optional<String> apiKeyCommand();

        /** Static bearer token. */
        Optional<String> bearerToken();

        /** OpenAI wire format; not applicable to Anthropic. */
        Optional<CopilotProviderProperties.WireApi> wireApi();

        /** Azure API version; only applicable to the Azure type. */
        Optional<String> azureApiVersion();

        /** Additional request headers. */
        Map<String, String> headers();

        /** Prompt token limit. */
        OptionalInt maxPromptTokens();

        /** Output token limit. */
        OptionalInt maxOutputTokens();
    }

    /** Health check settings. */
    interface Health {
        /** Maximum time the health check waits for the Copilot runtime. */
        @WithDefault("5s")
        Duration timeout();

        /** Whether the health check may start a lazily configured client that is not yet connected. */
        @WithDefault("false")
        boolean startClient();

        /** Whether an unauthenticated runtime reports {@code DOWN}. */
        @WithDefault("true")
        boolean requireAuthentication();
    }

    /** Management endpoint settings. */
    interface Endpoint {
        /** Maximum time an endpoint operation waits for the Copilot runtime. */
        @WithDefault("5s")
        Duration timeout();

        /** Whether the endpoint may start a lazily configured client that is not yet connected. */
        @WithDefault("false")
        boolean startClient();

        /** Whether agent system instructions are included in the agents view. */
        @WithDefault("false")
        boolean showInstructions();

        /** Whether the sessions view lists sessions not created by this extension. */
        @WithDefault("false")
        boolean includeAllSessions();

        /** Whether the sessions view includes runtime-generated summaries, which may contain prompt content. */
        @WithDefault("false")
        boolean showSessionSummaries();
    }

    /** Security settings. */
    interface Security {
        /** Security identity token resolution. */
        Identity identity();

        /** Security identity token resolution. */
        interface Identity {
            /** Whether invocations without an authenticated identity or bearer token use ambient client credentials. */
            @WithDefault("false")
            boolean ambientFallback();
        }
    }

    /** Streaming settings. */
    interface Streaming {
        /** Events buffered per {@code Multi} subscriber before the stream fails with an overflow. */
        @WithDefault("256")
        int bufferCapacity();
    }
}
