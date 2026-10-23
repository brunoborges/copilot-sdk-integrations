package com.microsoft.copilot.integrations.springboot.autoconfigure;

import org.springframework.boot.autoconfigure.service.connection.ConnectionDetails;

/**
 * Details for connecting to an existing Copilot runtime server over TCP.
 *
 * <p>When a bean of this type is present, it takes precedence over {@code copilot.cli-url} and
 * {@code copilot.tcp-connection-token}, and local-runtime properties ({@code copilot.cli-path},
 * {@code copilot.cli-args}, {@code copilot.working-directory}) are ignored. Testcontainers
 * {@code @ServiceConnection} support contributes one automatically.
 */
public interface CopilotConnectionDetails extends ConnectionDetails {

    /** Server address: a port, {@code host:port}, or an HTTP-style URI without a path. */
    String cliUrl();

    /** Connection token expected by the server, or {@code null} when the server requires none. */
    default String tcpConnectionToken() {
        return null;
    }
}
