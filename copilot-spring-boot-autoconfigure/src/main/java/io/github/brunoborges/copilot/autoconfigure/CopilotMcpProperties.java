package io.github.brunoborges.copilot.autoconfigure;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.copilot.rpc.McpHttpServerConfig;
import com.github.copilot.rpc.McpServerConfig;
import com.github.copilot.rpc.McpStdioServerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

/**
 * Named MCP definitions; agents must explicitly select the servers they use.
 * @param servers local or remote MCP definitions keyed by server name
 */
@ConfigurationProperties("copilot.mcp")
public record CopilotMcpProperties(Map<String, Server> servers) {

    public CopilotMcpProperties {
        servers = servers == null ? Map.of() : Map.copyOf(servers);
        servers.keySet().forEach(name -> Assert.isTrue(name.matches("[A-Za-z][A-Za-z0-9._-]*"),
                "MCP server names must match [A-Za-z][A-Za-z0-9._-]*"));
    }

    /** Builds fresh SDK configuration objects for applications using native sessions. */
    public Map<String, McpServerConfig> toServerConfigs() {
        Map<String, McpServerConfig> configs = new LinkedHashMap<>();
        servers.forEach((name, server) -> configs.put(name, server.toServerConfig()));
        return Map.copyOf(configs);
    }

    public enum Transport {
        STDIO, HTTP
    }

    /**
     * Local process or remote HTTP/SSE settings. Empty tools expose no server tools;
     * use an explicit {@code "*"} entry to expose all. Timeout is a tool-call timeout.
     */
    public record Server(
            @DefaultValue("stdio") Transport type,
            String command,
            List<String> args,
            Map<String, String> env,
            String workingDirectory,
            String url,
            Map<String, String> headers,
            List<String> tools,
            Duration timeout) {

        public Server {
            Assert.notNull(type, "MCP server type is required");
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Map.copyOf(env);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            tools = tools == null ? List.of() : List.copyOf(tools);
            env.keySet().forEach(key -> Assert.hasText(key, "MCP environment variable names must not be blank"));
            headers.keySet().forEach(key -> Assert.hasText(key, "MCP header names must not be blank"));
            tools.forEach(tool -> Assert.hasText(tool, "MCP tool names must not be blank"));
            Assert.isTrue(timeout == null || (timeout.compareTo(Duration.ofMillis(1)) >= 0
                            && timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) <= 0),
                    "MCP timeout must be at least 1ms and fit a signed 32-bit millisecond value");
            if (type == Transport.STDIO) {
                Assert.hasText(command, "A stdio MCP server requires a command");
                Assert.isTrue(workingDirectory == null || !workingDirectory.isBlank(),
                        "MCP working-directory must not be blank");
                Assert.isTrue(url == null && headers.isEmpty(),
                        "A stdio MCP server cannot declare url or headers");
            } else {
                Assert.isTrue(command == null && args.isEmpty() && env.isEmpty() && workingDirectory == null,
                        "An HTTP MCP server cannot declare command, args, env, or working-directory");
                Assert.hasText(url, "An HTTP MCP server requires a url");
                String message = "MCP url must be an absolute HTTP(S) address without credentials or a fragment";
                URI address;
                try {
                    address = URI.create(url);
                } catch (IllegalArgumentException ex) {
                    // URI parser messages echo the input, which may contain credentials or tokens.
                    throw new IllegalArgumentException(message);
                }
                Assert.isTrue(("http".equalsIgnoreCase(address.getScheme()) || "https".equalsIgnoreCase(address.getScheme()))
                                && address.getHost() != null && address.getRawUserInfo() == null
                                && address.getRawFragment() == null
                                && (address.getPort() == -1 || (address.getPort() >= 1 && address.getPort() <= 65535)),
                        message);
            }
        }

        /** Creates an SDK transport configuration without connecting to a server. */
        public McpServerConfig toServerConfig() {
            McpServerConfig config = type == Transport.STDIO
                    ? new McpStdioServerConfig().setCommand(command).setArgs(args).setEnv(env)
                            .setWorkingDirectory(workingDirectory)
                    : new McpHttpServerConfig().setUrl(url).setHeaders(headers);
            config.setTools(tools);
            if (timeout != null) {
                config.setTimeout((int) timeout.toMillis());
            }
            return config;
        }

        @Override
        public String toString() {
            return "McpServer[type=" + type + "]";
        }
    }
}
