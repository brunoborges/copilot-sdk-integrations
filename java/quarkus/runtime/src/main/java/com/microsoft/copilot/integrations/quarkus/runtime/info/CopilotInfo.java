package com.microsoft.copilot.integrations.quarkus.runtime.info;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import com.microsoft.copilot.CopilotClient;
import com.microsoft.copilot.rpc.CopilotClientOptions;
import com.microsoft.copilot.rpc.InProcessRuntimeConnection;
import com.microsoft.copilot.rpc.SpawnedStdioRuntimeConnection;
import com.microsoft.copilot.rpc.SpawnedTcpRuntimeConnection;
import com.microsoft.copilot.rpc.TcpRuntimeConnection;

/** Version and connection-mode facts reported by the info contributor and management endpoint. */
public final class CopilotInfo {

    public static final String UNKNOWN = "unknown";
    private static final String EXTENSION_PROPERTIES = "META-INF/maven/com.microsoft/copilot-quarkus/pom.properties";
    private static final String SDK_PROPERTIES = "META-INF/maven/com.microsoft/copilot-sdk/pom.properties";

    private CopilotInfo() {
    }

    public static String extensionVersion() {
        return version(CopilotInfo.class, EXTENSION_PROPERTIES);
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
