package com.microsoft.copilot.integrations.micronaut.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.microsoft.copilot.integrations.micronaut.test.CopilotTestRuntime;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;

@MicronautTest
class AdvisorControllerTest {

    @Inject
    CopilotTestRuntime copilot;

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void answersOverHttp() {
        copilot.agent("micronaut-advisor").replies("Use constructor injection.");

        assertEquals("Use constructor injection.", post("/advisor/c-1", "How should I inject beans?"));
    }

    @Test
    void answersWithABoundAgent() {
        copilot.agent("bound-advisor").replies("Bound answer.");

        assertEquals("Bound answer.", post("/advisor/once", "Hi"));
    }

    @Test
    void streamsMessages() {
        copilot.agent("streaming-advisor").replies("Streamed answer.");

        assertTrue(post("/advisor/c-2/stream", "Stream please").contains("Streamed answer."));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsHealthAndDiagnostics() {
        // The scripted runtime does not answer status queries, so only the check's registration is asserted.
        Map<String, Object> health;
        try {
            health = client.toBlocking().retrieve(HttpRequest.GET("/health/readiness"), Map.class);
        } catch (HttpClientResponseException e) {
            health = e.getResponse().getBody(Map.class).orElseThrow();
        }
        assertTrue(((Map<String, Object>) health.get("details")).containsKey("copilot"));

        List<Map<String, Object>> agents = client.toBlocking()
                .retrieve(HttpRequest.GET("/copilot/agents"), Argument.listOf(Argument.mapOf(String.class, Object.class)));
        assertTrue(agents.stream().anyMatch(agent -> "micronaut-advisor".equals(agent.get("name"))));
    }

    private String post(String uri, String body) {
        return client.toBlocking().retrieve(HttpRequest.POST(uri, body).contentType(MediaType.TEXT_PLAIN_TYPE)
                .accept(MediaType.TEXT_PLAIN_TYPE, MediaType.TEXT_EVENT_STREAM_TYPE));
    }
}
