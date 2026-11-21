package com.microsoft.copilot.integrations.springboot.autoconfigure;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

import com.microsoft.copilot.rpc.CopilotClientOptions;

import com.microsoft.copilot.integrations.core.internal.CopilotRuntimeConnections;

/**
 * @param enabled whether to auto-configure a client
 * @param eagerStart whether to connect during application startup
 * @param startupTimeout maximum wait for an eager connection
 * @param cliPath explicit executable path for a managed local runtime
 * @param cliArgs extra arguments for a managed local runtime
 * @param workingDirectory working directory for a managed local runtime
 * @param logLevel runtime log level for a runtime started by the client; omitted values retain the runtime default
 *        and it cannot be combined with {@code cliUrl}
 * @param cliUrl existing server address (port, host:port, or HTTP-style URI), attached over TCP
 * @param tcpConnectionToken authentication token for an existing TCP server
 * @param connection runtime transport: {@code stdio} or the experimental {@code inprocess}; omitted values use the
 *        {@code cli-url} server if set, else {@code COPILOT_SDK_DEFAULT_CONNECTION=inprocess}, else stdio
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

        return CopilotRuntimeConnections.clientOptions("copilot.", new CopilotRuntimeConnections.Settings(cliPath,
                cliArgs, workingDirectory, logLevel, cliUrl, tcpConnectionToken,
                connection == null ? null : CopilotRuntimeConnections.Transport.valueOf(connection.name())));
    }

    CopilotClientOptions toOptions(CopilotConnectionDetails details) {
        Assert.notNull(details.cliUrl(), "CopilotConnectionDetails.cliUrl() is required");
        // The external server owns its runtime settings, so copilot.log-level does not apply to it.
        return new CopilotProperties(enabled, eagerStart, startupTimeout, null, List.of(), null, null,
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
}
