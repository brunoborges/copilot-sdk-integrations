package io.github.brunoborges.copilot.micronaut.health;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.reactivestreams.Publisher;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.GetAuthStatusResponse;
import com.github.copilot.rpc.GetStatusResponse;

import io.github.brunoborges.copilot.micronaut.CopilotRuntimeAccess;
import io.github.brunoborges.copilot.micronaut.config.CopilotConfigs;
import io.github.brunoborges.copilot.micronaut.config.CopilotRuntimeConfig;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.endpoint.health.HealthEndpoint;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import io.micronaut.management.health.indicator.annotation.Readiness;
import io.micronaut.scheduling.TaskExecutors;

/**
 * Readiness indicator reporting Copilot runtime connectivity, version, and authentication status.
 * <p>
 * A client that has not been started yet reports {@code UP} with its state and reason instead of being started by
 * the check, unless {@code micronaut.copilot.health.start-client} is enabled. Credentials are never reported.
 */
@Readiness
@Singleton
@Requires(beans = HealthEndpoint.class)
@Requires(property = CopilotRuntimeConfig.PREFIX + ".health.enabled", notEquals = "false")
@Requires(property = CopilotRuntimeConfig.PREFIX + ".enabled", notEquals = "false")
public class CopilotHealthIndicator implements HealthIndicator {

    static final String NAME = "copilot";

    private final CopilotClient client;
    private final Duration timeout;
    private final boolean startClient;
    private final boolean requireAuthentication;
    private final ExecutorService executor;

    @Inject
    public CopilotHealthIndicator(CopilotClient client, CopilotRuntimeConfig config,
            @Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        this(client, config.health().timeout(), config.health().startClient(), config.health().requireAuthentication(),
                executor);
    }

    public CopilotHealthIndicator(CopilotClient client, Duration timeout, boolean startClient,
            boolean requireAuthentication, ExecutorService executor) {
        CopilotConfigs.requirePositive(timeout, "micronaut.copilot.health.timeout");
        this.client = client;
        this.timeout = timeout;
        this.startClient = startClient;
        this.requireAuthentication = requireAuthentication;
        this.executor = executor;
    }

    /** Runs the check on the blocking executor; runtime RPCs must not block the event loop. */
    @Override
    public Publisher<HealthResult> getResult() {
        return Publishers.fromCompletableFuture(() -> CompletableFuture.supplyAsync(this::check, executor));
    }

    /** Performs the check on the calling thread. */
    public HealthResult check() {
        var builder = new Builder();
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

    private HealthResult failure(Builder builder, String reason, Exception failure) {
        return builder.down().withData("state", CopilotRuntimeAccess.state(client)).withData("reason", reason)
                .withData("error", CopilotRuntimeAccess.error(failure)).build();
    }

    private static void data(Builder builder, String key, Object value) {
        if (value instanceof Boolean flag) {
            builder.withData(key, flag);
        } else if (value instanceof Number number) {
            builder.withData(key, number.longValue());
        } else if (value != null) {
            builder.withData(key, value.toString());
        }
    }

    private static final class Builder {
        private final Map<String, Object> details = new LinkedHashMap<>();
        private HealthStatus status = HealthStatus.UP;

        Builder withData(String key, Object value) {
            details.put(key, value);
            return this;
        }

        Builder status(boolean up) {
            status = up ? HealthStatus.UP : HealthStatus.DOWN;
            return this;
        }

        Builder up() {
            return status(true);
        }

        Builder down() {
            return status(false);
        }

        HealthResult build() {
            return HealthResult.builder(NAME, status).details(details).build();
        }
    }
}
