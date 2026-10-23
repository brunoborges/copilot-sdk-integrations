package io.github.brunoborges.copilot.micronaut.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.github.brunoborges.copilot.micronaut.test.CopilotTestRuntime;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;

@MicronautTest(startApplication = false)
class MicronautAdvisorTest {

    @Inject
    CopilotTestRuntime copilot;

    @Inject
    MicronautAdvisor advisor;

    @Test
    void answersWithTheReferenceTool() {
        copilot.agent("micronaut-advisor")
                .invokesTool("describe_term", Map.of("term", "micronaut"))
                .repliesUsing(turn -> "In short: " + turn.toolResult("describe_term"));

        String answer = advisor.answer("conversation-1", "What is Micronaut?").join();

        assertEquals("In short: Micronaut computes dependency injection at compile time, so applications start fast "
                + "without reflection.", answer);
        assertEquals(List.of("What is Micronaut?"), copilot.prompts("micronaut-advisor"));
        assertEquals("Focus on Micronaut applications. Answer without using tools.",
                copilot.sessionConfig("micronaut-advisor").getSystemMessage().getContent());
        assertEquals(1, copilot.toolCalls("micronaut-advisor").size());
        assertTrue(copilot.toolCalls("micronaut-advisor").get(0).succeeded());
    }

    @Test
    void continuesTheSameConversation() {
        copilot.agent("micronaut-advisor").replies("Dependencies are injected.").replies("DI, summarized.");

        advisor.answer("conversation-2", "Explain dependency injection.").join();
        String followUp = advisor.answer("conversation-2", "Summarize.").join();

        assertEquals("DI, summarized.", followUp);
        assertTrue(copilot.lastSession("micronaut-advisor").resumed());
    }
}
