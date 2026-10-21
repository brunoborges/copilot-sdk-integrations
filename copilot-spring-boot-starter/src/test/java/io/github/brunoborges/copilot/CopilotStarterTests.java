package io.github.brunoborges.copilot;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.ConnectionState;
import com.github.copilot.CopilotClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotStarterTests {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void consumerDiscoversClientAndUsesRealSdkOverTcp(boolean eagerStart) throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var requests = executor.submit(() -> serveConnection(server));

            new ApplicationContextRunner().withUserConfiguration(ConsumerApplication.class)
                    .withPropertyValues("copilot.cli-url=127.0.0.1:" + server.getLocalPort(),
                            "copilot.tcp-connection-token=test-token",
                            "copilot.eager-start=" + eagerStart, "copilot.startup-timeout=5s")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(CopilotClient.class);
                        var client = context.getBean(CopilotClient.class);
                        assertThat(client.getState()).isEqualTo(
                                eagerStart ? ConnectionState.CONNECTED : ConnectionState.DISCONNECTED);
                        assertThat(client.ping("hello").get(5, TimeUnit.SECONDS).message()).isEqualTo("hello");
                    });

            var received = requests.get(5, TimeUnit.SECONDS);
            assertThat(received).extracting(request -> request.get("method").asText())
                    .containsExactly("connect", "ping");
            assertThat(received.getFirst().get("params").get("token").asText()).isEqualTo("test-token");
        }
    }

    private static List<JsonNode> serveConnection(ServerSocket server) throws IOException {
        var mapper = new ObjectMapper();
        var requests = new ArrayList<JsonNode>();
        try (var socket = server.accept()) {
            socket.setSoTimeout(5000);
            var input = new BufferedInputStream(socket.getInputStream());
            var output = socket.getOutputStream();
            String header;
            while ((header = readHeader(input)) != null) {
                int length = Integer.parseInt(header.substring("Content-Length: ".length(), header.indexOf("\r\n")));
                var request = mapper.readTree(input.readNBytes(length));
                requests.add(request);
                String method = request.get("method").asText();
                Map<String, Object> result = switch (method) {
                    case "connect" -> Map.of("protocolVersion", 2);
                    case "ping" -> Map.of("protocolVersion", 2, "message", request.get("params").get("message").asText());
                    default -> throw new IOException("Unexpected SDK request: " + method);
                };
                byte[] response = mapper.writeValueAsBytes(
                        Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
                output.write(("Content-Length: " + response.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(response);
                output.flush();
            }
        }
        return requests;
    }

    static String readHeader(InputStream input) throws IOException {
        var header = new StringBuilder();
        while (!header.toString().endsWith("\r\n\r\n")) {
            int value = input.read();
            if (value == -1) {
                if (header.isEmpty()) {
                    return null;
                }
                throw new IOException("Unexpected EOF in JSON-RPC header");
            }
            header.append((char) value);
        }
        return header.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ConsumerApplication {
    }
}
