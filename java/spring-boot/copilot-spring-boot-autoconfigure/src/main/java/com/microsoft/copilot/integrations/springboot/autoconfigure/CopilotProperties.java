package com.microsoft.copilot.integrations.springboot.autoconfigure;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

import com.github.copilot.rpc.CopilotClientOptions;

import com.microsoft.copilot.integrations.core.internal.CopilotRuntimeConnections;

/**
 * @param enabled whether to auto-configure a client
 * @param eagerStart whether to connect during application startup
 * @param startupTimeout maximum wait for an eager connection
 * @param cliPath explicit executable path for a managed local runtime
 * @param cliArgs extra arguments for a managed local runtime
 * @param workingDirectory working directory for a managed local runtime
 * @param logLevel runtime log level; omitted values retain the SDK default
 * @param cliUrl existing server address (port, host:port, or HTTP-style URI)
 * @param tcpConnectionToken authentication token for an existing TCP server
 * @param connection runtime transport: {@code stdio} or the experimental {@code inprocess}; omitted values keep the
 *        SDK default (the {@code cli-url} server, {@code COPILOT_SDK_DEFAULT_CONNECTION}, or stdio)
 */
@ConfigurationProperties("copilot")
public record CopilotProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("false") boolean eagerStart,
        @DefaultValue("30s") Duration startupTimeout,
        String cliPath,
        List<String> cliArgs,
        String workingDirectory,
        String logLevel,
        String cliUrl,
        String tcpConnectionToken,
        Connection connection) {

    /** How the client reaches the Copilot runtime. */
    public enum Connection {
        /** Child process over stdin/stdout, regardless of {@code COPILOT_SDK_DEFAULT_CONNECTION}. */
        STDIO,
        /** Host the runtime inside this JVM (experimental); needs JNA and the platform runtime artifact. */
        INPROCESS
    }

    public CopilotProperties {
        Assert.notNull(startupTimeout, "copilot.startup-timeout is required");
        Assert.isTrue(startupTimeout.compareTo(Duration.ZERO) > 0
                        && startupTimeout.compareTo(Duration.ofNanos(Long.MAX_VALUE)) <= 0,
                "copilot.startup-timeout must be positive and representable in nanoseconds");
        cliArgs = cliArgs == null ? List.of() : List.copyOf(cliArgs);
    }

    CopilotClientOptions toOptions() {
        requireText(cliPath, "copilot.cli-path");
        requireText(workingDirectory, "copilot.working-directory");
        requireText(logLevel, "copilot.log-level");
        requireText(cliUrl, "copilot.cli-url");
        requireText(tcpConnectionToken, "copilot.tcp-connection-token");
        cliArgs.forEach(arg -> Assert.hasText(arg, "copilot.cli-args must not contain blank arguments"));

        Assert.isTrue(cliUrl == null || (cliPath == null && cliArgs.isEmpty() && workingDirectory == null),
                "copilot.cli-url cannot be combined with copilot.cli-path, copilot.cli-args, or copilot.working-directory");
        Assert.isTrue(tcpConnectionToken == null || cliUrl != null,
                "copilot.tcp-connection-token requires copilot.cli-url");

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
        if (tcpConnectionToken != null) {
            options.setTcpConnectionToken(tcpConnectionToken);
        }
        if (connection == Connection.STDIO) {
            CopilotRuntimeConnections.useStdio(options, "copilot.");
        } else if (connection == Connection.INPROCESS) {
            CopilotRuntimeConnections.useInProcess(options, "copilot.");
        }
        return options;
    }

    CopilotClientOptions toOptions(CopilotConnectionDetails details) {
        Assert.notNull(details.cliUrl(), "CopilotConnectionDetails.cliUrl() is required");
        return new CopilotProperties(enabled, eagerStart, startupTimeout, null, List.of(), null, logLevel,
                details.cliUrl(), details.tcpConnectionToken(), null).toOptions();
    }

    @Override
    public String toString() {
        return "CopilotProperties[enabled=" + enabled + ", eagerStart=" + eagerStart
                + ", startupTimeout=" + startupTimeout + ", connection=" + connection + "]";
    }

    private static void requireText(String value, String name) {
        Assert.isTrue(value == null || !value.isBlank(), name + " must not be blank");
    }

    private static void validateServerAddress(String address) {
        String message = "copilot.cli-url must specify a host and port (1-65535), or just a port; "
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
