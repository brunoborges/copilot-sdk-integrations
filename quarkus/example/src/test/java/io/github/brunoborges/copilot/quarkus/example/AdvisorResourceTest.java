package io.github.brunoborges.copilot.quarkus.example;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.github.brunoborges.copilot.quarkus.test.CopilotTestRuntime;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class AdvisorResourceTest {

    @Inject
    CopilotTestRuntime copilot;

    @Test
    void answersOverHttp() {
        copilot.agent("quarkus-advisor").replies("Use constructor injection.");

        given().contentType("text/plain").body("How should I inject beans?")
                .when().post("/advisor/c-1")
                .then().statusCode(200).body(equalTo("Use constructor injection."));
    }

    @Test
    void answersWithABoundAgent() {
        copilot.agent("bound-advisor").replies("Bound answer.");

        given().contentType("text/plain").body("Hi").when().post("/advisor/once")
                .then().statusCode(200).body(equalTo("Bound answer."));
    }

    @Test
    void streamsMessages() {
        copilot.agent("streaming-advisor").replies("Streamed answer.");

        given().contentType("text/plain").body("Stream please").when().post("/advisor/c-2/stream")
                .then().statusCode(200).body(containsString("Streamed answer."));
    }

    @Test
    void reportsHealthAndDiagnostics() {
        // The scripted runtime does not answer status queries, so only the check's registration is asserted.
        given().when().get("/q/health/ready").then().body("checks.name", hasItem("copilot"));
        given().when().get("/q/copilot/agents").then().statusCode(200)
                .body("name", hasItem("quarkus-advisor"));
    }
}
