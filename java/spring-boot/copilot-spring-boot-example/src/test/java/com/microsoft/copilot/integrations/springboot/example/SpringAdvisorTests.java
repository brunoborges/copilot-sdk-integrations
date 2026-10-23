package com.microsoft.copilot.integrations.springboot.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.microsoft.copilot.integrations.springboot.test.AutoConfigureCopilotTestRuntime;
import com.microsoft.copilot.integrations.springboot.test.CopilotTestRuntime;

@SpringBootTest(args = "--help")
@AutoConfigureCopilotTestRuntime
class SpringAdvisorTests {

    @Autowired
    CopilotTestRuntime copilot;

    @Autowired
    SpringAdvisor advisor;

    @Test
    void answersWithTheReferenceTool() {
        copilot.agent("spring-advisor")
                .invokesTool("describe_term", Map.of("term", "spring boot"))
                .repliesUsing(turn -> "In short: " + turn.toolResult("describe_term"));

        String answer = advisor.answer("conversation-1", "What is Spring Boot?").join();

        assertThat(answer).isEqualTo("In short: Spring Boot configures Spring applications using conventions "
                + "and conditional auto-configuration.");
        assertThat(copilot.prompts("spring-advisor")).containsExactly("What is Spring Boot?");
        assertThat(copilot.sessionConfig("spring-advisor").getSystemMessage().getContent())
                .isEqualTo("Focus on Spring Boot applications. Answer without using tools.");
        assertThat(copilot.toolCalls("spring-advisor")).singleElement()
                .satisfies(call -> assertThat(call.succeeded()).isTrue());
    }

    @Test
    void continuesTheSameConversation() {
        copilot.agent("spring-advisor").replies("Dependencies are injected.").replies("DI, summarized.");

        advisor.answer("conversation-2", "Explain dependency injection.").join();
        String followUp = advisor.answer("conversation-2", "Summarize.").join();

        assertThat(followUp).isEqualTo("DI, summarized.");
        assertThat(copilot.lastSession("spring-advisor").resumed()).isTrue();
    }
}
