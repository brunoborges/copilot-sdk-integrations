package io.github.brunoborges.copilot.quarkus.runtime.info;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import com.github.copilot.rpc.CopilotClientOptions;

import io.quarkus.info.runtime.spi.InfoContributor;

/**
 * Contributes a {@code copilot} section to {@code /q/info} with the extension version, Copilot SDK version,
 * and client connection mode. It never contacts the Copilot runtime.
 */
@Singleton
public class CopilotInfoContributor implements InfoContributor {

    private final String connectionMode;

    @Inject
    public CopilotInfoContributor(Instance<CopilotClientOptions> options) {
        this(CopilotInfo.connectionMode(options.isResolvable() ? options.get() : null));
    }

    public CopilotInfoContributor(String connectionMode) {
        this.connectionMode = connectionMode == null ? CopilotInfo.UNKNOWN : connectionMode;
    }

    @Override
    public String name() {
        return "copilot";
    }

    @Override
    public Map<String, Object> data() {
        Map<String, Object> copilot = new LinkedHashMap<>();
        copilot.put("extension", Map.of("version", CopilotInfo.extensionVersion()));
        copilot.put("sdk", Map.of("version", CopilotInfo.sdkVersion()));
        copilot.put("connection", Map.of("mode", connectionMode));
        return copilot;
    }
}
