package io.github.brunoborges.copilot.micronaut.info;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.reactivestreams.Publisher;

import com.github.copilot.rpc.CopilotClientOptions;

import io.github.brunoborges.copilot.micronaut.config.CopilotRuntimeConfig;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.management.endpoint.info.InfoEndpoint;
import io.micronaut.management.endpoint.info.InfoSource;

/**
 * Contributes a {@code copilot} section to the {@code info} endpoint with the integration version, Copilot SDK
 * version, and client connection mode. It never contacts the Copilot runtime.
 */
@Singleton
@Requires(beans = InfoEndpoint.class)
@Requires(property = CopilotRuntimeConfig.PREFIX + ".info.enabled", notEquals = "false")
@Requires(property = CopilotRuntimeConfig.PREFIX + ".enabled", notEquals = "false")
public class CopilotInfoSource implements InfoSource {

    private final String connectionMode;

    @Inject
    public CopilotInfoSource(BeanProvider<CopilotClientOptions> options) {
        this(CopilotInfo.connectionMode(options.isPresent() ? options.get() : null));
    }

    public CopilotInfoSource(String connectionMode) {
        this.connectionMode = connectionMode == null ? CopilotInfo.UNKNOWN : connectionMode;
    }

    @Override
    public Publisher<PropertySource> getSource() {
        return Publishers.just(PropertySource.of("copilot", Map.of("copilot", data())));
    }

    public Map<String, Object> data() {
        Map<String, Object> copilot = new LinkedHashMap<>();
        copilot.put("integration", Map.of("version", CopilotInfo.extensionVersion()));
        copilot.put("sdk", Map.of("version", CopilotInfo.sdkVersion()));
        copilot.put("connection", Map.of("mode", connectionMode));
        return copilot;
    }
}
