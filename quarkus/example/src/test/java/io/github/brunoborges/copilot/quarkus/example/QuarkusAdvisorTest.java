package io.github.brunoborges.copilot.quarkus.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.github.brunoborges.copilot.quarkus.test.CopilotTestRuntime;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class QuarkusAdvisorTest {

    @Inject
    CopilotTestRuntime copilot;

    @Inject
    QuarkusAdvisor advisor;

    @Test
    void answersWithTheReferenceTool() {
        copilot.agent("quarkus-advisor")
                .invokesTool("describe_term", Map.of("term", "quarkus"))
                .repliesUsing(turn -> "In short: " + turn.toolResult("describe_term"));

        String answer = advisor.answer("conversation-1", "What is Quarkus?").join();

        assertEquals("In short: Quarkus moves framework work to build time so Java applications start fast and use "
                + "little memory.", answer);
        assertEquals(List.of("What is Quarkus?"), copilot.prompts("quarkus-advisor"));
        assertEquals("Focus on Quarkus applications. Answer without using tools.",
                copilot.sessionConfig("quarkus-advisor").getSystemMessage().getContent());
        assertEquals(1, copilot.toolCalls("quarkus-advisor").size());
        assertTrue(copilot.toolCalls("quarkus-advisor").get(0).succeeded());
    }

    @Test
    void continuesTheSameConversation() {
        copilot.agent("quarkus-advisor").replies("Dependencies are injected.").replies("DI, summarized.");

        advisor.answer("conversation-2", "Explain dependency injection.").join();
        String followUp = advisor.answer("conversation-2", "Summarize.").join();

        assertEquals("DI, summarized.", followUp);
        assertTrue(copilot.lastSession("quarkus-advisor").resumed());
    }
}
