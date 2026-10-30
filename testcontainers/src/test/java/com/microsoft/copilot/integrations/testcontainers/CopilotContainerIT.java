package com.microsoft.copilot.integrations.testcontainers;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.ProviderConfig;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.SessionConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;

class CopilotContainerIT {

    private CopilotContainer runtime() {
        return new CopilotContainer(System.getProperty("copilot.test.image"));
    }

    private CopilotClient client(CopilotContainer runtime, String token) {
        return new CopilotClient(new CopilotClientOptions()
                .setCliUrl(runtime.getEndpoint()).setTcpConnectionToken(token));
    }

    @Test
    void connectsWithoutGithubCredentialsAndRejectsWrongConnectionSecret() throws Exception {
        try (var runtime = runtime().withCopilotPort(5432)) {
            runtime.start();
            try (var client = client(runtime, runtime.getTcpConnectionToken())) {
                client.start().get(30, TimeUnit.SECONDS);
                assertEquals("pong: container-ping", client.ping("container-ping").get(10, TimeUnit.SECONDS).message());
                String version = client.getStatus().get(10, TimeUnit.SECONDS).getVersion();
                assertNotNull(version);
                var cli = runtime.execInContainer("copilot", "--version");
                assertEquals(0, cli.getExitCode());
                assertTrue(cli.getStdout().contains(version), "Bundled CLI and runtime versions must match");
                assertEquals(0, runtime.execInContainer("test", "-s", "/opt/copilot/LICENSE.md").getExitCode());
                assertEquals("10001", runtime.execInContainer("id", "-u").getStdout().trim());
                assertEquals(runtime.getCliUrl().substring(7), runtime.getEndpoint());
            }
            try (var unauthorized = client(runtime, "wrong-connection-secret")) {
                var failure = assertThrows(ExecutionException.class,
                        () -> unauthorized.start().get(10, TimeUnit.SECONDS));
                assertTrue(failure.getMessage().contains("AUTHENTICATION_FAILED"));
            }
        }
    }

    @Test
    void sendsPromptThroughRealRuntimeToDeterministicProvider() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var mapper = new ObjectMapper();
        var provider = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        provider.createContext("/v1/chat/completions", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            boolean streaming = request.path("stream").asBoolean();
            String body = streaming ? """
                    data: {"id":"test","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello from the container runtime."},"finish_reason":null}]}

                    data: {"id":"test","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """ : """
                    {"id":"test","object":"chat.completion","created":1,"model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"Hello from the container runtime."},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":8,"total_tokens":18}}
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        provider.start();
        try {
            Testcontainers.exposeHostPorts(provider.getAddress().getPort());
            try (var runtime = runtime()) {
                runtime.start();
                try (var client = client(runtime, runtime.getTcpConnectionToken())) {
                    client.start().get(30, TimeUnit.SECONDS);
                    var config = new SessionConfig().setModel("test-model").setStreaming(true)
                            .setAvailableTools(java.util.List.of())
                            .setOnPermissionRequest((request, invocation) ->
                                    java.util.concurrent.CompletableFuture.completedFuture(
                                            PermissionRequestResult.reject("Tools are disabled in this test")))
                            .setProvider(new ProviderConfig().setType("openai").setWireApi("completions")
                                    .setApiKey("local-test-key")
                                    .setBaseUrl("http://host.testcontainers.internal:" + provider.getAddress().getPort() + "/v1"));
                    try (var session = client.createSession(config).get(30, TimeUnit.SECONDS)) {
                        var answer = session.sendAndWait(new MessageOptions().setPrompt("Say hello."),
                                30_000).get(45, TimeUnit.SECONDS);
                        assertEquals("Hello from the container runtime.", answer.getData().content());
                        assertTrue(requests.stream().anyMatch(request ->
                                        "test-model".equals(request.path("model").asText())
                                                && request.path("messages").toString().contains("Say hello.")),
                                "The real runtime must forward the model and application prompt to the test provider");
                    }
                }
            }
        } finally {
            provider.stop(0);
        }
    }
}
