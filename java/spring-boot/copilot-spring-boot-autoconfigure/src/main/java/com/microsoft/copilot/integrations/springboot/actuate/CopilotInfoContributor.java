package com.microsoft.copilot.integrations.springboot.actuate;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import com.microsoft.copilot.CopilotClient;
import com.microsoft.copilot.rpc.CopilotClientOptions;
import com.microsoft.copilot.rpc.InProcessRuntimeConnection;
import com.microsoft.copilot.rpc.SpawnedStdioRuntimeConnection;
import com.microsoft.copilot.rpc.SpawnedTcpRuntimeConnection;
import com.microsoft.copilot.rpc.TcpRuntimeConnection;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

/**
 * Contributes a {@code copilot} section with the starter version, Microsoft Copilot SDK version, and
 * client connection mode. It never contacts the Copilot runtime.
 */
public class CopilotInfoContributor implements InfoContributor {

    static final String UNKNOWN = "unknown";
    private static final String STARTER_PROPERTIES = "META-INF/copilot-spring-boot.properties";
    private static final String SDK_PROPERTIES = "META-INF/maven/com.microsoft/copilot-sdk/pom.properties";

    private final String connectionMode;

    public CopilotInfoContributor(String connectionMode) {
        this.connectionMode = connectionMode == null ? UNKNOWN : connectionMode;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> copilot = new LinkedHashMap<>();
        copilot.put("starter", Map.of("version", starterVersion()));
        copilot.put("sdk", Map.of("version", sdkVersion()));
        copilot.put("connection", Map.of("mode", connectionMode));
        builder.withDetail("copilot", copilot);
    }

    public static String starterVersion() {
        return version(CopilotInfoContributor.class, STARTER_PROPERTIES);
    }

    public static String sdkVersion() {
        return version(CopilotClient.class, SDK_PROPERTIES);
    }

    /** Describes how the client reaches the runtime without exposing addresses or credentials. */
    public static String connectionMode(CopilotClientOptions options) {
        if (options == null) {
            return UNKNOWN;
        }
        var connection = options.getRuntime();
        if (connection instanceof SpawnedStdioRuntimeConnection) {
            return "stdio";
        }
        if (connection instanceof SpawnedTcpRuntimeConnection) {
            return "tcp";
        }
        if (connection instanceof TcpRuntimeConnection) {
            return "external-server";
        }
        if (connection instanceof InProcessRuntimeConnection) {
            return "in-process";
        }
        if (connection != null) {
            return "custom";
        }
        // The SDK resolves an unset runtime from COPILOT_SDK_DEFAULT_CONNECTION, defaulting to in-process.
        String value = System.getenv("COPILOT_SDK_DEFAULT_CONNECTION");
        if ("stdio".equalsIgnoreCase(value)) {
            return "stdio";
        }
        return value == null || value.isEmpty() || "inprocess".equalsIgnoreCase(value) ? "in-process" : UNKNOWN;
    }

    private static String version(Class<?> type, String resource) {
        String version = type.getPackage() == null ? null : type.getPackage().getImplementationVersion();
        if (version != null) {
            return version;
        }
        ClassLoader loader = type.getClassLoader();
        if (loader != null) {
            try (InputStream input = loader.getResourceAsStream(resource)) {
                if (input != null) {
                    var properties = new Properties();
                    properties.load(input);
                    version = properties.getProperty("version");
                }
            } catch (IOException ex) {
                return UNKNOWN;
            }
        }
        return version == null || version.isBlank() || version.startsWith("@") ? UNKNOWN : version;
    }
}
