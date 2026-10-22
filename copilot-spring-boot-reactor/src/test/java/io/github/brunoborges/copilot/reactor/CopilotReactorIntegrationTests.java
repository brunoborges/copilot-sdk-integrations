package io.github.brunoborges.copilot.reactor;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.rpc.SessionConfig;
import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotAgentEvent;
import io.github.brunoborges.copilot.agent.UserMessage;
import io.github.brunoborges.copilot.agent.CopilotAgents;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class CopilotReactorIntegrationTests {

    @Test
    void autoConfiguredProxyRequestsStreamingAndCleansUpBeforeCompletion() {
        var client = mock(CopilotClient.class);
        var session = mock(CopilotSession.class);
        var subscription = mock(Closeable.class);
        var handler = new AtomicReference<Consumer<SessionEvent>>();
        var reply = new AssistantMessageEvent();
        var data = mock(AssistantMessageEvent.AssistantMessageEventData.class);
        when(data.content()).thenReturn("reply");
        reply.setData(data);
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        when(session.on(any())).thenAnswer(invocation -> {
            handler.set(invocation.getArgument(0));
            return subscription;
        });
        when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> {
            handler.get().accept(reply);
            handler.get().accept(new SessionIdleEvent());
            return CompletableFuture.completedFuture(reply);
        });
        new ApplicationContextRunner().withUserConfiguration(ReactiveApplication.class)
                .withBean(CopilotClient.class, () -> client).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ReactiveAgent.class);
                    var flux = context.getBean(ReactiveAgent.class).stream("conversation", "hello");
                    verifyNoInteractions(client);
                    StepVerifier.create(flux)
                            .assertNext(event -> assertThat(event.event()).isSameAs(reply))
                            .assertNext(event -> assertThat(event.event()).isInstanceOf(SessionIdleEvent.class))
                            .verifyComplete();
                    ArgumentCaptor<SessionConfig> config = ArgumentCaptor.captor();
                    verify(client).createSession(config.capture());
                    assertThat(config.getValue().isStreaming()).isTrue();
                    verify(subscription).close();
                    verify(session).close();
                });
    }

    @Test
    void invalidBufferSizeFailsBeforeAnyInvocation() {
        new ApplicationContextRunner().withUserConfiguration(ReactiveApplication.class)
                .withPropertyValues("copilot.reactor.buffer-capacity=0").run(context ->
                        assertThat(context).hasFailed());
    }

    @Test
    void successfulEventStreamDoesNotRequireAFinalAssistantTextMessage() {
        var client = mock(CopilotClient.class);
        var session = mock(CopilotSession.class);
        var handler = new AtomicReference<Consumer<SessionEvent>>();
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        when(session.on(any())).thenAnswer(invocation -> {
            handler.set(invocation.getArgument(0));
            return mock(Closeable.class);
        });
        when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> {
            handler.get().accept(new SessionIdleEvent());
            return CompletableFuture.completedFuture(null);
        });
        new ApplicationContextRunner().withUserConfiguration(ReactiveApplication.class)
                .withBean(CopilotClient.class, () -> client).run(context -> {
                    StepVerifier.create(context.getBean(ReactiveAgent.class).stream("conversation", "hello"))
                            .assertNext(event -> assertThat(event.event()).isInstanceOf(SessionIdleEvent.class))
                            .verifyComplete();
                    verify(session).close();
                });
    }

    @Test
    void cancellationDoesNotAbortSdkWorkOrReleaseCapacityBeforeCleanup() {
        var client = mock(CopilotClient.class);
        var session = mock(CopilotSession.class);
        var response = new CompletableFuture<AssistantMessageEvent>();
        var started = new CountDownLatch(1);
        var reply = new AssistantMessageEvent();
        var data = mock(AssistantMessageEvent.AssistantMessageEventData.class);
        when(data.content()).thenReturn("reply");
        reply.setData(data);
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        when(session.on(any())).thenReturn(mock(Closeable.class));
        when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> {
            started.countDown();
            return response;
        });
        new ApplicationContextRunner().withUserConfiguration(ReactiveApplication.class)
                .withBean(CopilotClient.class, () -> client)
                .withPropertyValues("copilot.execution.max-concurrent-invocations=1",
                        "copilot.execution.max-outstanding-invocations=1").run(context -> {
                    try {
                        var agent = context.getBean(ReactiveAgent.class);
                        var subscriber = agent.stream("first", "hello").subscribe();
                        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                        subscriber.dispose();
                        assertThat(response.isCancelled()).isFalse();
                        verify(session, never()).close();
                        StepVerifier.create(agent.stream("other", "hello"))
                                .expectError(RejectedExecutionException.class).verify(java.time.Duration.ofSeconds(5));
                    } finally {
                        response.complete(reply);
                    }
                });
        verify(session).close();
    }

    @Test
    void aColdBoundStreamCannotStartAfterItsHandleCloses() {
        var client = mock(CopilotClient.class);
        new ApplicationContextRunner().withUserConfiguration(ReactiveApplication.class)
                .withBean(CopilotClient.class, () -> client).run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(BoundReactiveAgent.class);
                    Flux<CopilotAgentEvent> stream;
                    try (var instance = context.getBean(CopilotAgents.class).create(BoundReactiveAgent.class)) {
                        stream = instance.agent().stream("hello");
                        verifyNoInteractions(client);
                    }
                    StepVerifier.create(stream).expectErrorMessage("Copilot agent instance is closed").verify();
                    verifyNoInteractions(client);
                });
    }

    @Test
    void boundStreamUsesItsGeneratedIdentityAndNormalSessionCleanup() {
        var client = mock(CopilotClient.class);
        var session = mock(CopilotSession.class);
        var handler = new AtomicReference<Consumer<SessionEvent>>();
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        when(session.on(any())).thenAnswer(invocation -> {
            handler.set(invocation.getArgument(0));
            return mock(Closeable.class);
        });
        when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> {
            handler.get().accept(new SessionIdleEvent());
            return CompletableFuture.completedFuture(null);
        });
        new ApplicationContextRunner().withUserConfiguration(ReactiveApplication.class)
                .withBean(CopilotClient.class, () -> client).run(context -> {
                    try (var instance = context.getBean(CopilotAgents.class).create(BoundReactiveAgent.class)) {
                        String key = instance.conversationId();
                        StepVerifier.create(instance.agent().stream("hello"))
                                .assertNext(event -> assertThat(event.context().conversationId()).isEqualTo(key))
                                .verifyComplete();
                        verify(session).close();
                    }
                });
    }

    @CopilotAgent(name = "reactive-test", instructions = "Be concise.")
    public interface ReactiveAgent {
        Flux<CopilotAgentEvent> stream(@ConversationId String conversationId, @UserMessage String message);
    }

    @CopilotAgent(name = "bound-reactive-test", instructions = "Be concise.")
    public interface BoundReactiveAgent {
        Flux<CopilotAgentEvent> stream(@UserMessage String message);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ReactiveApplication {
    }
}
