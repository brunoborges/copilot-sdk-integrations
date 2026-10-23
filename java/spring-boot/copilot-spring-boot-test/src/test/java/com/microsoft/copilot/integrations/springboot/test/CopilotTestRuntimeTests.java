package com.microsoft.copilot.integrations.springboot.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.github.copilot.ConnectionState;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.ToolDefinition;

import com.microsoft.copilot.integrations.core.CopilotAgentTelemetry;

class CopilotTestRuntimeTests {

    record Verdict(String outcome, int score) {
    }

    private final CopilotTestRuntime runtime = new CopilotTestRuntime();

    @Test
    void behavesLikeAConnectedClient() {
        var client = runtime.client();
        client.start().join();

        assertThat(client.getState()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(client.getSessionMetadata("missing").join()).isNull();

        var session = client.createSession(new SessionConfig().setSessionId("s1").setModel("gpt-5")).join();
        assertThat(session.getSessionId()).isEqualTo("s1");
        assertThat(client.getSessionMetadata("s1").join().getSessionId()).isEqualTo("s1");
        assertThat(client.listSessions().join()).extracting(m -> m.getSessionId()).containsExactly("s1");
        assertThat(client.getLastSessionId().join()).isEqualTo("s1");
        assertThat(client.createSession(new SessionConfig().setSessionId("s1"))).isCompletedExceptionally();
        assertThat(client.resumeSession("missing", new ResumeSessionConfig())).isCompletedExceptionally();

        client.deleteSession("s1").join();
        assertThat(client.getSessionMetadata("s1").join()).isNull();
        client.stop().join();
        client.close();
    }

    @Test
    void returnsStructuredOutput() {
        runtime.anyAgent().replyJson(new Verdict("approved", 9));
        var session = runtime.client().createSession(new SessionConfig()).join();

        var verdict = session.sendAndWait(new MessageOptions().setPrompt("Judge"), Verdict.class, 1_000).join();
        assertThat(verdict).isEqualTo(new Verdict("approved", 9));
        assertThat(session.sendAndWait("Again").join().getData().content())
                .isEqualTo("{\"outcome\":\"approved\",\"score\":9}");
        assertThat(runtime.receivedPrompts()).extracting(CopilotTestPrompt::responseType)
                .containsExactly(Verdict.class, null);
    }

    @Test
    void attributesCallsToAgents() {
        runtime.agent("a").replies("from a");
        runtime.anyAgent().replies("fallback");
        var telemetry = runtime.telemetry();

        String reply;
        var call = telemetry.start("a", "");
        try (CopilotAgentTelemetry.Scope scope = call.openScope()) {
            reply = runtime.client().createSession(new SessionConfig()).join().sendAndWait("hi").join().getData()
                    .content();
        }
        assertThat(reply).isEqualTo("from a");
        assertThat(runtime.client().createSession(new SessionConfig()).join().sendAndWait("hey").join().getData()
                .content()).isEqualTo("fallback");
        try (var scope = runtime.actAs("b")) {
            runtime.client().createSession(new SessionConfig()).join().sendAndWait("yo").join();
        }
        assertThat(runtime.prompts("a")).containsExactly("hi");
        assertThat(runtime.prompts(null)).containsExactly("hey");
        assertThat(runtime.prompts("b")).containsExactly("yo");
        assertThat(runtime.sessions("a")).hasSize(1);
    }

    @Test
    void invokesRegisteredToolsAndDeliversTypedListeners() {
        var tool = ToolDefinition.create("add", "Adds", Map.of("type", "object"),
                invocation -> java.util.concurrent.CompletableFuture.completedFuture(
                        (Integer) invocation.getArguments().get("a") + (Integer) invocation.getArguments().get("b")));
        runtime.anyAgent().invokesTool("add", Map.of("a", 2, "b", 3)).streams("5")
                .repliesUsing(turn -> "sum=" + turn.toolResult("add"));
        var session = runtime.client().createSession(new SessionConfig().setTools(List.of(tool))).join();
        var deltas = new CopyOnWriteArrayList<String>();
        session.on(AssistantMessageDeltaEvent.class, event -> deltas.add(event.getData().deltaContent()));

        assertThat(session.sendAndWait("2+3").join().getData().content()).isEqualTo("sum=5");
        assertThat(deltas).containsExactly("5");
        assertThat(runtime.toolCalls()).singleElement().satisfies(call -> assertThat(call.result()).isEqualTo(5));
        assertThat(session.getMessages().join()).last().isInstanceOf(SessionIdleEvent.class);
    }

    @Test
    void failsUnknownToolCallsClearly() {
        runtime.anyAgent().invokesTool("missing").replies("never");
        var session = runtime.client().createSession(new SessionConfig().setSessionId("s")).join();

        assertThatThrownBy(() -> session.sendAndWait("go").join()).hasRootCauseMessage(
                "Scripted tool call 'missing' does not match a tool registered with Copilot session 's'; "
                        + "registered tools: []");
    }

    @Test
    void abortsInFlightTurns() throws Exception {
        runtime.anyAgent().delays(Duration.ofSeconds(30)).replies("never");
        var session = runtime.client().createSession(new SessionConfig().setSessionId("s")).join();
        var events = new CopyOnWriteArrayList<SessionEvent>();
        session.on(events::add);

        var reply = session.sendAndWait(new MessageOptions().setPrompt("slow"), 10_000);
        session.abort().join();

        assertThat(reply.get()).isNull();
        assertThat(runtime.aborts()).containsExactly("s");
        assertThat(events).last().isInstanceOfSatisfying(SessionIdleEvent.class,
                idle -> assertThat(idle.getData().aborted()).isTrue());
    }

    @Test
    void rejectsUnsupportedOperations() {
        assertThatThrownBy(() -> runtime.client().listModels()).isInstanceOf(UnsupportedOperationException.class)
                .hasMessageStartingWith("CopilotClient.listModels() is not supported by CopilotTestRuntime");
        var session = runtime.client().createSession(new SessionConfig()).join();
        assertThatThrownBy(() -> session.compact()).isInstanceOf(UnsupportedOperationException.class)
                .hasMessageStartingWith("CopilotSession.compact() is not supported");
        session.close();
        assertThat(session.sendAndWait("late")).isCompletedExceptionally();
    }

    @Test
    void resetForgetsEverything() {
        runtime.anyAgent().replies("hi");
        runtime.client().createSession(new SessionConfig().setSessionId("s")).join().sendAndWait("hello").join();

        runtime.reset();

        assertThat(runtime.sessions()).isEmpty();
        assertThat(runtime.prompts()).isEmpty();
        assertThat(runtime.client().getSessionMetadata("s").join()).isNull();
        assertThat(runtime.client().createSession(new SessionConfig()).join().sendAndWait("again"))
                .failsWithin(Duration.ofSeconds(1));
    }
}
