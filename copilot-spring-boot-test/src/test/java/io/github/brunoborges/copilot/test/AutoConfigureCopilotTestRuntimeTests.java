package io.github.brunoborges.copilot.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.github.copilot.CopilotClient;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.ToolExecutionCompleteEvent;
import com.github.copilot.generated.ToolExecutionStartEvent;
import com.github.copilot.generated.UserMessageEvent;

import io.github.brunoborges.copilot.test.app.AdvisorApplication;
import io.github.brunoborges.copilot.test.app.CodeReviewer;
import io.github.brunoborges.copilot.test.app.Glossary;
import io.github.brunoborges.copilot.test.app.SpringAdvisor;

@SpringBootTest(classes = AdvisorApplication.class)
@AutoConfigureCopilotTestRuntime
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutoConfigureCopilotTestRuntimeTests {

    @Autowired
    CopilotTestRuntime copilot;

    @Autowired
    CopilotClient client;

    @Autowired
    SpringAdvisor advisor;

    @Autowired
    CodeReviewer reviewer;

    @Autowired
    Glossary glossary;

    @BeforeEach
    void clearGlossary() {
        glossary.lookups().clear();
    }

    @Test
    @Order(1)
    void replacesTheCopilotClientAndRoutesRepliesPerAgent() {
        copilot.agent("spring-advisor").replies("Prefer constructor injection.");
        copilot.agent("code-reviewer").repliesWith(code -> "LGTM: " + code.length() + " chars");

        assertThat(client).isSameAs(copilot.client());
        assertThat(advisor.ask("c1", "How should I inject beans?").join()).isEqualTo("Prefer constructor injection.");
        assertThat(reviewer.review("c1", "class A {}").join()).isEqualTo("LGTM: 10 chars");

        assertThat(copilot.prompts("spring-advisor")).containsExactly("How should I inject beans?");
        assertThat(copilot.prompts("code-reviewer")).containsExactly("class A {}");
        var session = copilot.lastSession("spring-advisor");
        assertThat(session.model()).isEqualTo("gpt-5");
        assertThat(session.toolNames()).containsExactly("describe_term");
        assertThat(session.systemMessage().getContent()).contains("Answer Spring questions.");
        assertThat(session.config().getSessionId()).startsWith("agent-");
    }

    @Test
    @Order(2)
    void runsSpringToolBeansForScriptedToolCalls() {
        copilot.agent("spring-advisor")
                .invokesTool("describe_term", Map.of("term", "bean"))
                .repliesUsing(turn -> "Glossary says " + turn.toolResult("describe_term"));

        assertThat(advisor.ask("c2", "What is a bean?").join()).isEqualTo("Glossary says bean: a Spring concept");

        assertThat(glossary.lookups()).containsExactly("bean");
        assertThat(copilot.toolCalls("spring-advisor")).singleElement().satisfies(call -> {
            assertThat(call.toolName()).isEqualTo("describe_term");
            assertThat(call.arguments()).containsEntry("term", "bean");
            assertThat(call.result()).isEqualTo("bean: a Spring concept");
            assertThat(call.succeeded()).isTrue();
        });
    }

    @Test
    @Order(3)
    void recordsToolFailuresAndContinuesTheTurn() {
        copilot.agent("spring-advisor").invokesTool("describe_term", Map.of("term", " "))
                .repliesUsing(turn -> turn.toolCalls().getFirst().succeeded() ? "ok" : "tool failed");

        assertThat(advisor.ask("c3", "?").join()).isEqualTo("tool failed");
        assertThat(copilot.toolCalls().getFirst().failure()).hasMessageContaining("term must not be blank");
    }

    @Test
    @Order(4)
    void streamsEventsToAgentObservers() {
        copilot.agent("spring-advisor").invokesTool("describe_term", Map.of("term", "context"))
                .streamsReply("Hello", ", ", "world");

        var transcript = advisor.stream("c4", "Greet me");

        assertThat(transcript.reply()).isEqualTo("Hello, world");
        assertThat(transcript.events()).extracting(event -> event.getClass().getSimpleName()).containsExactly(
                UserMessageEvent.class.getSimpleName(), ToolExecutionStartEvent.class.getSimpleName(),
                ToolExecutionCompleteEvent.class.getSimpleName(), AssistantMessageDeltaEvent.class.getSimpleName(),
                AssistantMessageDeltaEvent.class.getSimpleName(), AssistantMessageDeltaEvent.class.getSimpleName(),
                AssistantMessageEvent.class.getSimpleName(), "SessionIdleEvent");
    }

    @Test
    @Order(5)
    void resumesPersistedConversations() {
        copilot.agent("spring-advisor").replies("first").replies("second");

        assertThat(advisor.ask("c5", "one").join()).isEqualTo("first");
        assertThat(advisor.ask("c5", "two").join()).isEqualTo("second");
        assertThat(advisor.ask("c5", "three").join()).isEqualTo("second");

        assertThat(copilot.sessions("spring-advisor")).singleElement().satisfies(session -> {
            assertThat(session.resumed()).isTrue();
            assertThat(session.resumes()).hasSize(2);
            assertThat(session.prompts()).containsExactly("one", "two", "three");
            assertThat(session.toolNames()).containsExactly("describe_term");
        });
    }

    @Test
    @Order(6)
    void propagatesScriptedFailures() {
        copilot.agent("spring-advisor").fails(new IllegalStateException("model overloaded"));

        assertThatThrownBy(() -> advisor.ask("c6", "hi").join()).isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("model overloaded");
    }

    @Test
    @Order(7)
    void failsClearlyWithoutAScript() {
        assertThatThrownBy(() -> advisor.ask("c7", "hi").join())
                .hasRootCauseMessage("No scripted reply for agent 'spring-advisor'; use runtime.agent(\"spring-advisor\")"
                        + ".replies(...) or runtime.anyAgent().replies(...)");
    }

    @Test
    @Order(8)
    void fallsBackToTheAnyAgentScript() {
        copilot.anyAgent().repliesWith(prompt -> "echo " + prompt);

        assertThat(reviewer.review("c8", "x").join()).isEqualTo("echo x");
    }

    @Test
    @Order(9)
    void timesOutLikeTheSdk() {
        copilot.anyAgent().delays(Duration.ofSeconds(30)).replies("too late");

        assertThatThrownBy(() -> copilot.client().createSession(new com.github.copilot.rpc.SessionConfig()).join()
                .sendAndWait(new com.github.copilot.rpc.MessageOptions().setPrompt("slow"), 50).join())
                .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class)
                .hasRootCauseMessage("sendAndWait timed out after 50ms");
    }

    @Test
    @Order(10)
    void isResetAfterEachTest() {
        assertThat(copilot.prompts()).isEmpty();
        assertThat(copilot.sessions()).isEmpty();
        assertThat(copilot.toolCalls()).isEmpty();
        assertThatThrownBy(() -> advisor.ask("c1", "hi").join()).hasRootCauseInstanceOf(IllegalStateException.class);
    }
}
