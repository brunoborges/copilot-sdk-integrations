package io.github.brunoborges.copilot;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.SystemMessageMode;
import com.github.copilot.rpc.SectionOverride;
import com.github.copilot.rpc.SectionOverrideAction;
import com.github.copilot.rpc.SystemMessageConfig;
import com.github.copilot.rpc.SystemMessageSections;
import example.agentwire.WireAgent;
import io.github.brunoborges.copilot.agent.EnableCopilotAgents;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotAgentWireTests {

    @ParameterizedTest
    @EnumSource(SystemMessageMode.class)
    void realSdkCreatesResumesAndReceivesEventsThroughTypedAgent(SystemMessageMode mode) throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var requests = executor.submit(() -> serve(server));
            new ApplicationContextRunner().withUserConfiguration(WireApplication.class)
                    .withPropertyValues("copilot.cli-url=127.0.0.1:" + server.getLocalPort(),
                            "test.instructions-mode=" + mode.name(), "copilot.agents.wire-agent.response-timeout=2s")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(WireAgent.class);
                        var agent = context.getBean(WireAgent.class);
                        assertThat(agent.answer("conversation", "first").get(10, TimeUnit.SECONDS)).isEqualTo("reply:first");
                        assertThat(agent.answer("conversation", "follow-up").get(10, TimeUnit.SECONDS))
                                .isEqualTo("reply:follow-up");
                    });
            var received = requests.get(5, TimeUnit.SECONDS);
            assertThat(received).extracting(request -> request.get("method").asText()).containsExactly(
                    "connect", "session.getMetadata", "session.create", "session.send", "session.detach",
                    "session.getMetadata", "session.resume", "session.send", "session.detach");
            var created = received.get(2).get("params");
            var resumed = received.get(6).get("params");
            assertThat(resumed.get("sessionId")).isEqualTo(created.get("sessionId"));
            assertThat(created.get("systemMessage").get("mode").asText()).isEqualTo(mode.getValue());
            assertThat(resumed.get("systemMessage")).isEqualTo(created.get("systemMessage"));
        }
    }

    private static List<JsonNode> serve(ServerSocket server) throws IOException {
        var mapper = new ObjectMapper();
        var requests = new ArrayList<JsonNode>();
        var sessions = new HashSet<String>();
        try (var socket = server.accept()) {
            socket.setSoTimeout(5000);
            var input = new BufferedInputStream(socket.getInputStream());
            var output = socket.getOutputStream();
            String header;
            while ((header = CopilotStarterTests.readHeader(input)) != null) {
                int length = Integer.parseInt(header.substring("Content-Length: ".length(), header.indexOf("\r\n")));
                var request = mapper.readTree(input.readNBytes(length));
                requests.add(request);
                String method = request.get("method").asText();
                var params = request.get("params");
                Object result = switch (method) {
                    case "connect" -> Map.of("protocolVersion", 2);
                    case "session.getMetadata" -> {
                        var metadata = mapper.createObjectNode();
                        String id = params.get("sessionId").asText();
                        metadata.set("session", sessions.contains(id)
                                ? mapper.valueToTree(Map.of("sessionId", id)) : mapper.nullNode());
                        yield metadata;
                    }
                    case "session.create", "session.resume" -> {
                        String id = params.get("sessionId").asText();
                        sessions.add(id);
                        yield Map.of("sessionId", id);
                    }
                    case "session.send" -> Map.of("messageId", "message-" + requests.size());
                    case "session.detach" -> Map.of("success", true);
                    default -> throw new IOException("Unexpected SDK request: " + method);
                };
                write(output, mapper, Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
                if (method.equals("session.send")) {
                    String id = params.get("sessionId").asText();
                    write(output, mapper, Map.of("jsonrpc", "2.0", "method", "session.event", "params",
                            Map.of("sessionId", id, "event", Map.of("type", "assistant.message", "id", UUID.randomUUID().toString(),
                                    "data", Map.of("messageId", "assistant", "content", "reply:" + params.get("prompt").asText())))));
                    write(output, mapper, Map.of("jsonrpc", "2.0", "method", "session.event", "params",
                            Map.of("sessionId", id, "event", Map.of("type", "session.idle", "id", UUID.randomUUID().toString()))));
                }
            }
        }
        return requests;
    }

    private static void write(OutputStream output, ObjectMapper mapper, Object message) throws IOException {
        byte[] json = mapper.writeValueAsBytes(message);
        output.write(("Content-Length: " + json.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(json);
        output.flush();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableCopilotAgents(basePackageClasses = WireAgent.class)
    static class WireApplication {
        @Bean
        SystemMessageConfig wireInstructions(Environment environment) {
            var mode = environment.getRequiredProperty("test.instructions-mode", SystemMessageMode.class);
            var config = new SystemMessageConfig().setMode(mode).setContent("Be concise.");
            if (mode == SystemMessageMode.CUSTOMIZE) {
                config.setSections(Map.of(SystemMessageSections.TONE,
                        new SectionOverride().setAction(SectionOverrideAction.APPEND).setContent("Use Java examples.")));
            }
            return config;
        }
    }
}
