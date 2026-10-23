package com.microsoft.copilot.integrations.springboot.actuate;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.InProcessRuntimeConnection;
import com.github.copilot.rpc.StdioRuntimeConnection;
import com.github.copilot.rpc.TcpRuntimeConnection;
import com.github.copilot.rpc.UriRuntimeConnection;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

/**
 * Contributes a {@code copilot} section with the starter version, Copilot SDK version, and
 * client connection mode. It never contacts the Copilot runtime.
 */
public class CopilotInfoContributor implements InfoContributor {

    static final String UNKNOWN = "unknown";
    private static final String STARTER_PROPERTIES = "META-INF/copilot-spring-boot.properties";
    private static final String SDK_PROPERTIES = "META-INF/maven/com.github/copilot-sdk-java/pom.properties";

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
        var connection = options.getConnection();
        if (connection instanceof StdioRuntimeConnection) {
            return "stdio";
        }
        if (connection instanceof TcpRuntimeConnection) {
            return "tcp";
        }
        if (connection instanceof UriRuntimeConnection) {
            return "external-server";
        }
        if (connection instanceof InProcessRuntimeConnection) {
            return "in-process";
        }
        if (connection != null) {
            return "custom";
        }
        if (options.getCliUrl() != null) {
            return "external-server";
        }
        return options.isUseStdio() ? "stdio" : "tcp";
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
