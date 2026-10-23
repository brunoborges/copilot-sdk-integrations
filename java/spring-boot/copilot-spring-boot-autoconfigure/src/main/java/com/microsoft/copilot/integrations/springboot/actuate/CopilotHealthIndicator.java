package com.microsoft.copilot.integrations.springboot.actuate;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.GetAuthStatusResponse;
import com.github.copilot.rpc.GetStatusResponse;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.util.Assert;

/**
 * Reports Copilot runtime connectivity, version, and authentication status.
 * <p>
 * A client that has not been started yet reports {@code UNKNOWN} instead of being started by the
 * health check, unless {@code startClient} is enabled. Credentials and tokens are never reported.
 */
public class CopilotHealthIndicator implements HealthIndicator {

    private final CopilotClient client;
    private final Duration timeout;
    private final boolean startClient;
    private final boolean requireAuthentication;

    public CopilotHealthIndicator(CopilotClient client, Duration timeout, boolean startClient,
            boolean requireAuthentication) {
        Assert.notNull(client, "CopilotClient is required");
        Assert.isTrue(timeout != null && timeout.compareTo(Duration.ZERO) > 0, "Health timeout must be positive");
        this.client = client;
        this.timeout = timeout;
        this.startClient = startClient;
        this.requireAuthentication = requireAuthentication;
    }

    @Override
    public Health health() {
        var builder = new Health.Builder();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            String unavailable = CopilotRuntimeAccess.connect(client, startClient, deadline);
            builder.withDetail("state", CopilotRuntimeAccess.state(client));
            if (unavailable != null) {
                return (unavailable.equals(CopilotRuntimeAccess.FAILED) ? builder.down() : builder.unknown())
                        .withDetail("reason", unavailable).build();
            }
            var statusFuture = client.getStatus();
            var authFuture = client.getAuthStatus();
            GetStatusResponse status = CopilotRuntimeAccess.await(statusFuture, deadline);
            GetAuthStatusResponse auth = CopilotRuntimeAccess.await(authFuture, deadline);
            if (status != null) {
                detail(builder, "version", status.getVersion());
                builder.withDetail("protocolVersion", status.getProtocolVersion());
            }
            boolean authenticated = auth != null && auth.isAuthenticated();
            builder.withDetail("authenticated", authenticated);
            if (auth != null) {
                detail(builder, "authType", auth.getAuthType());
                detail(builder, "host", auth.getHost());
                detail(builder, "login", auth.getLogin());
            }
            if (!authenticated && requireAuthentication) {
                return builder.down().withDetail("reason", "Copilot runtime is not authenticated").build();
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

    private Health failure(Health.Builder builder, String reason, Exception failure) {
        String state;
        try {
            state = CopilotRuntimeAccess.state(client);
        } catch (RuntimeException ex) {
            state = "UNKNOWN";
        }
        return builder.down().withDetail("state", state).withDetail("reason", reason)
                .withDetail("error", CopilotRuntimeAccess.error(failure)).build();
    }

    private static void detail(Health.Builder builder, String key, Object value) {
        if (value != null) {
            builder.withDetail(key, value);
        }
    }
}
