package com.microsoft.copilot.integrations.micronaut.test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.StdioRuntimeConnection;

import io.micronaut.context.ApplicationContext;

class CopilotConnectionConfigTest {

    private static final Map<String, Object> BASE = Map.of("micronaut.copilot.test-runtime.enabled", "false",
            "micronaut.copilot.eager-start", "false");

    @Test
    void stdioConnectionPinsTheLocalRuntime() {
        try (var context = ApplicationContext.run(with("micronaut.copilot.connection", "stdio",
                "micronaut.copilot.cli-path", "/opt/copilot"))) {
            var connection = assertInstanceOf(StdioRuntimeConnection.class,
                    context.getBean(CopilotClientOptions.class).getConnection());
            assertTrue("/opt/copilot".equals(connection.getPath()));
        }
    }

    @Test
    void inProcessConnectionReportsRejectedSettings() {
        var error = assertThrows(RuntimeException.class, () -> ApplicationContext.run(with(
                "micronaut.copilot.connection", "inprocess", "micronaut.copilot.cli-url", "localhost:3000")));
        assertMessage(error, "micronaut.copilot.connection=inprocess cannot be combined with micronaut.copilot.cli-url");
    }

    @Test
    void rejectsUnknownConnections() {
        var error = assertThrows(RuntimeException.class,
                () -> ApplicationContext.run(with("micronaut.copilot.connection", "tcp", "x", "y")));
        assertMessage(error, "micronaut.copilot.connection must be stdio or inprocess");
    }

    private static Map<String, Object> with(String key1, Object value1, String key2, Object value2) {
        var properties = new java.util.HashMap<>(BASE);
        properties.put(key1, value1);
        properties.put(key2, value2);
        return properties;
    }

    private static void assertMessage(Throwable error, String expected) {
        Throwable cause = error;
        while (cause != null && (cause.getMessage() == null || !cause.getMessage().contains(expected))) {
            cause = cause.getCause();
        }
        assertTrue(cause != null, () -> "Unexpected failure: " + error);
    }
}
