package com.microsoft.copilot.integrations.quarkus.runtime.config;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import com.github.copilot.rpc.CopilotClientOptions;

import com.microsoft.copilot.integrations.core.internal.Assert;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentExecutionLimits;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentSettings;
import com.microsoft.copilot.integrations.core.internal.CopilotRuntimeConnections;

/** Converts {@link CopilotRuntimeConfig} into validated settings and SDK options. */
public final class CopilotConfigs {

    private CopilotConfigs() {
    }

    public static CopilotAgentExecutionLimits execution(CopilotRuntimeConfig config) {
        return new CopilotAgentExecutionLimits(config.execution().maxConcurrentInvocations(),
                config.execution().maxOutstandingInvocations());
    }

    public static CopilotAgentSettings agents(CopilotRuntimeConfig config) {
        var agents = new LinkedHashMap<String, CopilotAgentSettings.Agent>();
        config.agents().forEach((name, agent) -> {
            var permissions = agent.permissions();
            agents.put(name, new CopilotAgentSettings.Agent(
                    agent.model().orElse(null),
                    agent.reasoningEffort().orElse(null),
                    agent.responseTimeout(),
                    agent.mcpServers().map(CopilotConfigs::noneAsEmpty).orElse(null),
                    agent.provider().map(value -> "none".equals(value) ? "" : value).orElse(null),
                    agent.availableTools().map(CopilotConfigs::noneAsEmpty).orElse(null),
                    agent.excludedTools().map(CopilotConfigs::noneAsEmpty).orElse(null),
                    agent.excludedBuiltInAgents().map(CopilotConfigs::noneAsEmpty).orElse(null),
                    agent.builtInTools().orElse(null),
                    agent.workingDirectory().orElse(null),
                    agent.additionalDirectories().map(CopilotConfigs::noneAsEmpty).orElse(null),
                    new CopilotAgentSettings.Permissions(
                            permissions.allow().orElse(List.of()),
                            permissions.allowTools().orElse(List.of()),
                            permissions.allowMcpServers().orElse(List.of()),
                            permissions.allowAgentTools(),
                            permissions.allowReadOnly()),
                    toolSearch(agent.toolSearch()),
                    agent.messageSource().map(value -> "none".equals(value) ? "" : value).orElse(null)));
        });
        return new CopilotAgentSettings(agents);
    }

    private static CopilotAgentSettings.ToolSearch toolSearch(CopilotRuntimeConfig.ToolSearch toolSearch) {
        Integer threshold = toolSearch.deferThreshold().isPresent() ? toolSearch.deferThreshold().getAsInt() : null;
        return toolSearch.enabled().isEmpty() && threshold == null ? null
                : new CopilotAgentSettings.ToolSearch(toolSearch.enabled().orElse(null), threshold);
    }

    public static CopilotMcpProperties mcp(CopilotRuntimeConfig config) {
        var servers = new LinkedHashMap<String, CopilotMcpProperties.Server>();
        config.mcp().servers().forEach((name, server) -> servers.put(name, new CopilotMcpProperties.Server(
                server.type(), server.command().orElse(null), server.args().orElse(null), server.env(),
                server.workingDirectory().orElse(null), server.url().orElse(null), server.headers(),
                server.tools().orElse(null), server.timeout().orElse(null))));
        return new CopilotMcpProperties(servers);
    }

    public static CopilotProviderProperties providers(CopilotRuntimeConfig config) {
        var providers = new LinkedHashMap<String, CopilotProviderProperties.Provider>();
        config.providers().forEach((name, provider) -> providers.put(name, new CopilotProviderProperties.Provider(
                provider.type(), provider.baseUrl(), provider.apiKey().orElse(null),
                provider.apiKeyCommand().orElse(null), provider.bearerToken().orElse(null),
                provider.wireApi().orElse(null),
                provider.azureApiVersion().map(CopilotProviderProperties.Azure::new).orElse(null),
                provider.headers(),
                provider.maxPromptTokens().isPresent() ? provider.maxPromptTokens().getAsInt() : null,
                provider.maxOutputTokens().isPresent() ? provider.maxOutputTokens().getAsInt() : null)));
        return new CopilotProviderProperties(providers);
    }

    /** Validates connection settings and builds SDK client options. */
    public static CopilotClientOptions clientOptions(CopilotRuntimeConfig config) {
        requirePositive(config.startupTimeout(), "quarkus.copilot.startup-timeout");
        String cliPath = text(config.cliPath(), "quarkus.copilot.cli-path");
        String workingDirectory = text(config.workingDirectory(), "quarkus.copilot.working-directory");
        String logLevel = text(config.logLevel(), "quarkus.copilot.log-level");
        String cliUrl = text(config.cliUrl(), "quarkus.copilot.cli-url");
        String token = text(config.tcpConnectionToken(), "quarkus.copilot.tcp-connection-token");
        List<String> cliArgs = config.cliArgs().orElse(List.of());
        cliArgs.forEach(arg -> Assert.hasText(arg, "quarkus.copilot.cli-args must not contain blank arguments"));

        Assert.isTrue(cliUrl == null || (cliPath == null && cliArgs.isEmpty() && workingDirectory == null),
                "quarkus.copilot.cli-url cannot be combined with quarkus.copilot.cli-path, quarkus.copilot.cli-args, "
                        + "or quarkus.copilot.working-directory");
        Assert.isTrue(token == null || cliUrl != null,
                "quarkus.copilot.tcp-connection-token requires quarkus.copilot.cli-url");

        var options = new CopilotClientOptions();
        if (cliPath != null) {
            options.setCliPath(cliPath);
        }
        if (!cliArgs.isEmpty()) {
            options.setCliArgs(cliArgs.toArray(String[]::new));
        }
        if (workingDirectory != null) {
            options.setCwd(workingDirectory);
        }
        if (logLevel != null) {
            options.setLogLevel(logLevel);
        }
        if (cliUrl != null) {
            validateServerAddress(cliUrl);
            options.setCliUrl(cliUrl).setUseStdio(false);
        }
        if (token != null) {
            options.setTcpConnectionToken(token);
        }
        var connection = config.connection().orElse(null);
        if (connection == CopilotRuntimeConfig.Connection.STDIO) {
            CopilotRuntimeConnections.useStdio(options, "quarkus.copilot.");
        } else if (connection == CopilotRuntimeConfig.Connection.INPROCESS) {
            CopilotRuntimeConnections.useInProcess(options, "quarkus.copilot.");
        }
        return options;
    }

    public static void requirePositive(Duration timeout, String name) {
        Assert.notNull(timeout, name + " is required");
        Assert.isTrue(timeout.compareTo(Duration.ZERO) > 0 && timeout.compareTo(Duration.ofNanos(Long.MAX_VALUE)) <= 0,
                name + " must be positive and representable in nanoseconds");
    }

    private static List<String> noneAsEmpty(List<String> values) {
        return values.size() == 1 && "none".equals(values.get(0)) ? List.of() : values;
    }

    private static String text(Optional<String> value, String name) {
        Assert.isTrue(value.isEmpty() || !value.get().isBlank(), name + " must not be blank");
        return value.orElse(null);
    }

    private static void validateServerAddress(String address) {
        String message = "quarkus.copilot.cli-url must specify a host and port (1-65535), or just a port; "
                + "credentials, paths, queries, and fragments are not supported";
        URI uri;
        try {
            String normalized = address.matches("[0-9]+") ? "http://localhost:" + address : address;
            uri = URI.create(normalized.contains("://") ? normalized : "http://" + normalized);
        } catch (IllegalArgumentException ex) {
            // URI parser messages may contain credentials; expose only the property name.
            throw new IllegalArgumentException(message);
        }
        Assert.isTrue(("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null && uri.getPort() > 0 && uri.getPort() <= 65535
                        && uri.getRawUserInfo() == null
                        && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                        && uri.getRawQuery() == null && uri.getRawFragment() == null,
                message);
    }
}
