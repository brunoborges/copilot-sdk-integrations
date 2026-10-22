package io.github.brunoborges.copilot.agent.spring;

import io.github.brunoborges.copilot.agent.*;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.SessionConfig;
import io.github.brunoborges.copilot.agent.fixtures.TestAgent;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentDefinition;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentExecutionLimits;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BoundedAgentExecutionTests {

    @Test
    void boundsActiveAndOutstandingWorkAndReleasesCapacityAfterCleanup() throws Exception {
        var client = mock(CopilotClient.class);
        var replies = Map.of("one", new CompletableFuture<AssistantMessageEvent>(),
                "two", new CompletableFuture<AssistantMessageEvent>(), "three", new CompletableFuture<AssistantMessageEvent>());
        var started = Map.of("one", new CountDownLatch(1), "two", new CountDownLatch(1), "three", new CountDownLatch(1));
        setup(client, replies, started);
        var definition = definition();
        try (var invoker = new CopilotAgentInvoker(client, CopilotAgentTelemetry.NOOP,
                new CopilotAgentExecutionLimits(1, 2))) {
            try {
                var first = invoker.invoke(definition, "first", "one");
                assertThat(started.get("one").await(5, TimeUnit.SECONDS)).isTrue();
                var second = invoker.invoke(definition, "second", "two");
                assertThat(started.get("two").getCount()).isEqualTo(1);
                assertThat(invoker.executionStats()).isEqualTo(new CopilotAgentInvoker.ExecutionStats(1, 1, 1, 2));
                assertThatThrownBy(() -> invoker.invoke(definition, "third", "three").get())
                        .hasRootCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
                verify(client).getSessionMetadata(anyString());
                replies.get("one").complete(reply());
                first.get(5, TimeUnit.SECONDS);
                assertThat(started.get("two").await(5, TimeUnit.SECONDS)).isTrue();
                var third = invoker.invoke(definition, "third", "three");
                replies.get("two").complete(reply());
                second.get(5, TimeUnit.SECONDS);
                assertThat(started.get("three").await(5, TimeUnit.SECONDS)).isTrue();
                replies.get("three").complete(reply());
                third.get(5, TimeUnit.SECONDS);
                verify(client, times(3)).createSession(any());
            } finally {
                replies.values().forEach(future -> future.complete(reply()));
            }
        }
    }

    @Test
    void cancelledQueuedConversationDoesNotExecuteAndCannotGrowTheAdmissionEnvelope() throws Exception {
        var client = mock(CopilotClient.class);
        var replies = Map.of("one", new CompletableFuture<AssistantMessageEvent>(),
                "two", new CompletableFuture<AssistantMessageEvent>());
        var started = Map.of("one", new CountDownLatch(1), "two", new CountDownLatch(1));
        setup(client, replies, started);
        var definition = definition();
        try (var invoker = new CopilotAgentInvoker(client, CopilotAgentTelemetry.NOOP,
                new CopilotAgentExecutionLimits(1, 2))) {
            try {
                var first = invoker.invoke(definition, "same", "one");
                assertThat(started.get("one").await(5, TimeUnit.SECONDS)).isTrue();
                var queued = invoker.invoke(definition, "same", "two");
                queued.cancel(true);
                assertThatThrownBy(() -> invoker.invoke(definition, "other", "three").get())
                        .hasRootCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
                replies.get("one").complete(reply());
                first.get(5, TimeUnit.SECONDS);
            } finally {
                replies.values().forEach(future -> future.complete(reply()));
            }
        }
        verify(client).createSession(any());
        assertThat(started.get("two").getCount()).isEqualTo(1);
    }

    @Test
    void instrumentationFailureStillCompletesTheFutureAndReleasesAdmission() throws Exception {
        var client = mock(CopilotClient.class);
        var responses = Map.of("one", CompletableFuture.completedFuture(reply()));
        setup(client, responses, Map.of("one", new CountDownLatch(1)));
        CopilotAgentTelemetry telemetry = (agent, model) -> new CopilotAgentTelemetry.Call() {
            @Override
            public void stop() {
                throw new IllegalStateException("observation failed");
            }
        };
        try (var invoker = new CopilotAgentInvoker(client, telemetry, new CopilotAgentExecutionLimits(1, 1))) {
            for (int index = 0; index < 2; index++) {
                assertThatThrownBy(() -> invoker.invoke(definition(), "same", "one").get(5, TimeUnit.SECONDS))
                        .hasRootCauseMessage("observation failed");
            }
            verify(client, times(2)).createSession(any());
        }
    }

    private static void setup(CopilotClient client, Map<String, CompletableFuture<AssistantMessageEvent>> replies,
            Map<String, CountDownLatch> started) {
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            var session = mock(CopilotSession.class);
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            when(session.on(any())).thenReturn(mock(Closeable.class));
            when(session.sendAndWait(any(), anyLong())).thenAnswer(send -> {
                String prompt = send.<MessageOptions>getArgument(0).getPrompt();
                started.get(prompt).countDown();
                return replies.get(prompt);
            });
            return CompletableFuture.completedFuture(session);
        });
    }

    private static CopilotAgentDefinition definition() {
        return SpringTestSupport.definition(TestAgent.class, new DefaultListableBeanFactory(), new StandardEnvironment(),
                new DefaultResourceLoader(), new CopilotAgentsProperties(Map.of()));
    }

    private static AssistantMessageEvent reply() {
        var event = new AssistantMessageEvent();
        var data = mock(AssistantMessageEvent.AssistantMessageEventData.class);
        when(data.content()).thenReturn("reply");
        event.setData(data);
        return event;
    }
}
