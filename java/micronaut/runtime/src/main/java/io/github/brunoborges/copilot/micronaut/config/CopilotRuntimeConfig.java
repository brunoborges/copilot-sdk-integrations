package io.github.brunoborges.copilot.micronaut.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;

import io.micronaut.core.naming.conventions.StringConvention;
import io.micronaut.core.type.Argument;
import io.micronaut.core.value.PropertyResolver;

/**
 * The {@code micronaut.copilot.*} configuration, read from the Micronaut environment.
 * <p>
 * Named entries ({@code agents.<name>}, {@code mcp.servers.<name>}, {@code providers.<name>}) are discovered from
 * the configured keys, so any property source (YAML, properties, environment variables) can contribute them.
 */
public record CopilotRuntimeConfig(
        boolean eagerStart,
        Duration startupTimeout,
        Optional<String> cliPath,
        Optional<List<String>> cliArgs,
        Optional<String> workingDirectory,
        Optional<String> logLevel,
        Optional<String> cliUrl,
        Optional<String> tcpConnectionToken,
        Optional<Connection> connection,
        Execution execution,
        Map<String, Agent> agents,
        Mcp mcp,
        Map<String, Provider> providers,
        Health health,
        Endpoint endpoint,
        Security security,
        Streaming streaming) {

    public static final String PREFIX = "micronaut.copilot";

    /** How the client reaches the Copilot runtime ({@code micronaut.copilot.connection}). */
    public enum Connection {
        /** Child process over stdin/stdout, regardless of {@code COPILOT_SDK_DEFAULT_CONNECTION}. */
        STDIO,
        /** Runtime hosted inside this JVM (experimental); needs JNA and the platform runtime artifact. */
        INPROCESS
    }

    public record Execution(int maxConcurrentInvocations, int maxOutstandingInvocations) {
    }

    public record Agent(
            Optional<String> model,
            Optional<String> reasoningEffort,
            Duration responseTimeout,
            Optional<List<String>> mcpServers,
            Optional<String> provider,
            Optional<List<String>> availableTools,
            Optional<List<String>> excludedTools,
            Optional<List<String>> excludedBuiltInAgents,
            Optional<Boolean> builtInTools,
            Optional<String> workingDirectory,
            Optional<List<String>> additionalDirectories,
            Permissions permissions,
            ToolSearch toolSearch) {
    }

    public record ToolSearch(Optional<Boolean> enabled, OptionalInt deferThreshold) {
    }

    public record Permissions(
            Optional<List<String>> allow,
            Optional<List<String>> allowTools,
            Optional<List<String>> allowMcpServers,
            boolean allowAgentTools,
            boolean allowReadOnly) {
    }

    public record Mcp(Map<String, McpServer> servers) {
    }

    public record McpServer(
            CopilotMcpProperties.Transport type,
            Optional<String> command,
            Optional<List<String>> args,
            Map<String, String> env,
            Optional<String> workingDirectory,
            Optional<String> url,
            Map<String, String> headers,
            Optional<List<String>> tools,
            Optional<Duration> timeout) {
    }

    public record Provider(
            CopilotProviderProperties.Type type,
            String baseUrl,
            Optional<String> apiKey,
            Optional<String> apiKeyCommand,
            Optional<String> bearerToken,
            Optional<CopilotProviderProperties.WireApi> wireApi,
            Optional<String> azureApiVersion,
            Map<String, String> headers,
            OptionalInt maxPromptTokens,
            OptionalInt maxOutputTokens) {
    }

    public record Health(Duration timeout, boolean startClient, boolean requireAuthentication) {
    }

    public record Endpoint(Duration timeout, boolean startClient, boolean showInstructions,
            boolean includeAllSessions, boolean showSessionSummaries) {
    }

    public record Security(Identity identity) {
        public record Identity(boolean ambientFallback, String tokenAttribute) {
        }
    }

    public record Streaming(int bufferCapacity) {
    }

    private static Connection connection(String value) {
        String normalized = value.trim().replace("-", "").replace("_", "").toUpperCase(Locale.ROOT);
        for (Connection connection : Connection.values()) {
            if (connection.name().equals(normalized)) {
                return connection;
            }
        }
        throw new IllegalArgumentException(PREFIX + ".connection must be stdio or inprocess");
    }

    /** Reads the configuration, applying the documented defaults. */
    public static CopilotRuntimeConfig from(PropertyResolver properties) {
        var reader = new Reader(properties);
        return new CopilotRuntimeConfig(
                reader.bool(PREFIX + ".eager-start", false),
                reader.duration(PREFIX + ".startup-timeout", Duration.ofSeconds(30)),
                reader.text(PREFIX + ".cli-path"),
                reader.list(PREFIX + ".cli-args"),
                reader.text(PREFIX + ".working-directory"),
                reader.text(PREFIX + ".log-level"),
                reader.text(PREFIX + ".cli-url"),
                reader.text(PREFIX + ".tcp-connection-token"),
                reader.text(PREFIX + ".connection").map(CopilotRuntimeConfig::connection),
                new Execution(reader.integer(PREFIX + ".execution.max-concurrent-invocations", 8),
                        reader.integer(PREFIX + ".execution.max-outstanding-invocations", 64)),
                reader.named(PREFIX + ".agents", reader::agent),
                new Mcp(reader.named(PREFIX + ".mcp.servers", reader::mcpServer)),
                reader.named(PREFIX + ".providers", reader::provider),
                new Health(reader.duration(PREFIX + ".health.timeout", Duration.ofSeconds(5)),
                        reader.bool(PREFIX + ".health.start-client", false),
                        reader.bool(PREFIX + ".health.require-authentication", true)),
                new Endpoint(reader.duration(PREFIX + ".endpoint.timeout", Duration.ofSeconds(5)),
                        reader.bool(PREFIX + ".endpoint.start-client", false),
                        reader.bool(PREFIX + ".endpoint.show-instructions", false),
                        reader.bool(PREFIX + ".endpoint.include-all-sessions", false),
                        reader.bool(PREFIX + ".endpoint.show-session-summaries", false)),
                new Security(new Security.Identity(reader.bool(PREFIX + ".security.identity.ambient-fallback", false),
                        reader.text(PREFIX + ".security.identity.token-attribute").orElse("accessToken"))),
                new Streaming(reader.integer(PREFIX + ".streaming.buffer-capacity", 256)));
    }

    private record Reader(PropertyResolver properties) {

        interface Entry<T> {
            T read(String prefix);
        }

        <T> Map<String, T> named(String prefix, Entry<T> entry) {
            var result = new LinkedHashMap<String, T>();
            for (String name : new TreeSet<>(properties.getPropertyEntries(prefix))) {
                result.put(name, entry.read(prefix + "." + name));
            }
            return Map.copyOf(result);
        }

        Agent agent(String prefix) {
            return new Agent(text(prefix + ".model"), text(prefix + ".reasoning-effort"),
                    duration(prefix + ".response-timeout", Duration.ofSeconds(60)), list(prefix + ".mcp-servers"),
                    text(prefix + ".provider"), list(prefix + ".available-tools"), list(prefix + ".excluded-tools"),
                    list(prefix + ".excluded-built-in-agents"),
                    properties.getProperty(prefix + ".built-in-tools", Boolean.class),
                    text(prefix + ".working-directory"), list(prefix + ".additional-directories"),
                    new Permissions(list(prefix + ".permissions.allow"), list(prefix + ".permissions.allow-tools"),
                            list(prefix + ".permissions.allow-mcp-servers"),
                            bool(prefix + ".permissions.allow-agent-tools", false),
                            bool(prefix + ".permissions.allow-read-only", false)),
                    new ToolSearch(properties.getProperty(prefix + ".tool-search.enabled", Boolean.class),
                            optionalInt(prefix + ".tool-search.defer-threshold")));
        }

        McpServer mcpServer(String prefix) {
            return new McpServer(
                    text(prefix + ".type").map(value -> CopilotMcpProperties.Transport.valueOf(
                            value.trim().toUpperCase(Locale.ROOT))).orElse(CopilotMcpProperties.Transport.STDIO),
                    text(prefix + ".command"), list(prefix + ".args"), map(prefix + ".env"),
                    text(prefix + ".working-directory"), text(prefix + ".url"), map(prefix + ".headers"),
                    list(prefix + ".tools"), properties.getProperty(prefix + ".timeout", Duration.class));
        }

        Provider provider(String prefix) {
            return new Provider(
                    text(prefix + ".type").map(value -> CopilotProviderProperties.Type.valueOf(
                            value.trim().toUpperCase(Locale.ROOT))).orElse(CopilotProviderProperties.Type.OPENAI),
                    text(prefix + ".base-url").orElseThrow(() -> new IllegalArgumentException(
                            prefix + ".base-url is required")),
                    text(prefix + ".api-key"), text(prefix + ".api-key-command"), text(prefix + ".bearer-token"),
                    text(prefix + ".wire-api").map(value -> CopilotProviderProperties.WireApi.valueOf(
                            value.trim().toUpperCase(Locale.ROOT))),
                    text(prefix + ".azure-api-version"), map(prefix + ".headers"),
                    optionalInt(prefix + ".max-prompt-tokens"), optionalInt(prefix + ".max-output-tokens"));
        }

        Optional<String> text(String key) {
            return properties.getProperty(key, String.class);
        }

        Optional<List<String>> list(String key) {
            return properties.getProperty(key, Argument.listOf(String.class)).map(List::copyOf);
        }

        boolean bool(String key, boolean defaultValue) {
            return properties.getProperty(key, Boolean.class).orElse(defaultValue);
        }

        int integer(String key, int defaultValue) {
            return properties.getProperty(key, Integer.class).orElse(defaultValue);
        }

        OptionalInt optionalInt(String key) {
            return properties.getProperty(key, Integer.class).map(OptionalInt::of).orElse(OptionalInt.empty());
        }

        Duration duration(String key, Duration defaultValue) {
            return properties.getProperty(key, Duration.class).orElse(defaultValue);
        }

        Map<String, String> map(String key) {
            var result = new LinkedHashMap<String, String>();
            properties.getProperties(key, StringConvention.RAW)
                    .forEach((name, value) -> result.put(name, value == null ? null : value.toString()));
            return result;
        }
    }
}
