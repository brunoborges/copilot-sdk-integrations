package com.microsoft.copilot.integrations.quarkus.runtime.health;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.GetAuthStatusResponse;
import com.github.copilot.rpc.GetStatusResponse;

import com.microsoft.copilot.integrations.quarkus.runtime.CopilotRuntimeAccess;
import com.microsoft.copilot.integrations.quarkus.runtime.config.CopilotConfigs;
import com.microsoft.copilot.integrations.quarkus.runtime.config.CopilotRuntimeConfig;

/**
 * Readiness check reporting Copilot runtime connectivity, version, and authentication status.
 * <p>
 * A client that has not been started yet reports {@code UP} with its state and reason instead of being started by
 * the check, unless {@code quarkus.copilot.health.start-client} is enabled. Credentials are never reported.
 */
@Readiness
@ApplicationScoped
public class CopilotHealthCheck implements HealthCheck {

    static final String NAME = "copilot";

    private final CopilotClient client;
    private final Duration timeout;
    private final boolean startClient;
    private final boolean requireAuthentication;

    @Inject
    public CopilotHealthCheck(CopilotClient client, CopilotRuntimeConfig config) {
        this(client, config.health().timeout(), config.health().startClient(), config.health().requireAuthentication());
    }

    public CopilotHealthCheck(CopilotClient client, Duration timeout, boolean startClient, boolean requireAuthentication) {
        CopilotConfigs.requirePositive(timeout, "quarkus.copilot.health.timeout");
        this.client = client;
        this.timeout = timeout;
        this.startClient = startClient;
        this.requireAuthentication = requireAuthentication;
    }

    @Override
    public HealthCheckResponse call() {
        var builder = HealthCheckResponse.named(NAME);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            String unavailable = CopilotRuntimeAccess.connect(client, startClient, deadline);
            builder.withData("state", CopilotRuntimeAccess.state(client));
            if (unavailable != null) {
                // A lazily connecting client is not a readiness failure; it connects on first use.
                return builder.status(!unavailable.equals(CopilotRuntimeAccess.FAILED))
                        .withData("reason", unavailable).build();
            }
            var statusFuture = client.getStatus();
            var authFuture = client.getAuthStatus();
            GetStatusResponse status = CopilotRuntimeAccess.await(statusFuture, deadline);
            GetAuthStatusResponse auth = CopilotRuntimeAccess.await(authFuture, deadline);
            if (status != null) {
                data(builder, "version", status.getVersion());
                data(builder, "protocolVersion", status.getProtocolVersion());
            }
            boolean authenticated = auth != null && auth.isAuthenticated();
            builder.withData("authenticated", authenticated);
            if (auth != null) {
                data(builder, "authType", auth.getAuthType());
                data(builder, "host", auth.getHost());
                data(builder, "login", auth.getLogin());
            }
            if (!authenticated && requireAuthentication) {
                return builder.down().withData("reason", "Copilot runtime is not authenticated").build();
            }
            return builder.up().build();
        } catch (TimeoutException ex) {
            return failure(builder, "Copilot runtime did not respond within " + timeout, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failure(builder, "Interrupted while checking the Copilot runtime", ex);
        } catch (ExecutionException | RuntimeException ex) {
            return failure(builder, "Copilot runtime health check failed", ex);
        }
    }

    private HealthCheckResponse failure(HealthCheckResponseBuilder builder, String reason, Exception failure) {
        return builder.down().withData("state", CopilotRuntimeAccess.state(client)).withData("reason", reason)
                .withData("error", CopilotRuntimeAccess.error(failure)).build();
    }

    private static void data(HealthCheckResponseBuilder builder, String key, Object value) {
        if (value instanceof Boolean flag) {
            builder.withData(key, flag);
        } else if (value instanceof Number number) {
            builder.withData(key, number.longValue());
        } else if (value != null) {
            builder.withData(key, value.toString());
        }
    }
}
