package io.github.brunoborges.copilot.bound;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SessionMetadata;
import io.github.brunoborges.copilot.agent.CopilotAgentInstance;
import io.github.brunoborges.copilot.agent.CopilotAgentListener;
import io.github.brunoborges.copilot.agent.CopilotAgents;
import io.github.brunoborges.copilot.agent.CopilotConversationNotFoundException;
import io.github.brunoborges.copilot.bound.callbacks.CallbackAdvisor;
import io.github.brunoborges.copilot.bound.fixtures.BoundAdvisor;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsAutoConfiguration;
import io.github.brunoborges.copilot.autoconfigure.CopilotAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BoundAgentTests {

    @Test
    void distinctInstancesAreLazyAndUseIndependentConversations() {
        var runtime = new RuntimeStub();
        runner(runtime, BoundAdvisor.class).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(BoundAdvisor.class).hasSingleBean(CopilotAgents.class);
            var agents = context.getBean(CopilotAgents.class);
            try (var first = agents.create(BoundAdvisor.class); var second = agents.create(BoundAdvisor.class)) {
                assertThat(first.agent()).isNotSameAs(second.agent());
                assertThat(first.conversationId()).isNotEqualTo(second.conversationId()).isNotBlank();
                verifyNoInteractions(runtime.client);
                assertThat(first.agent().answer("first").get(5, TimeUnit.SECONDS)).isEqualTo("reply:first");
                assertThat(second.agent().answer("second").get(5, TimeUnit.SECONDS)).isEqualTo("reply:second");
                assertThat(first.agent().answer("follow-up").get(5, TimeUnit.SECONDS)).isEqualTo("reply:follow-up");
                assertThat(runtime.sessions).hasSize(3);
                assertThat(runtime.sessions.get(0).getSessionId()).isEqualTo(runtime.sessions.get(2).getSessionId())
                        .isNotEqualTo(runtime.sessions.get(1).getSessionId());
                assertThat(runtime.sessions.get(0)).isNotSameAs(runtime.sessions.get(2));
                runtime.sessions.forEach(session -> verify(session).close());
                verify(runtime.client, never()).close();
            }
            assertThat(runtime.history).hasSize(2);
        });
    }

    @Test
    void resumeReusesSavedIdentityAndNeverCreatesMissingHistory() {
        var runtime = new RuntimeStub();
        runner(runtime, BoundAdvisor.class).run(context -> {
            var agents = context.getBean(CopilotAgents.class);
            String saved;
            try (var first = agents.create(BoundAdvisor.class)) {
                saved = first.conversationId();
                first.agent().answer("initial").get(5, TimeUnit.SECONDS);
            }
            try (var resumed = agents.resume(BoundAdvisor.class, saved)) {
                assertThat(resumed.conversationId()).isEqualTo(saved);
                resumed.agent().answer("continue").get(5, TimeUnit.SECONDS);
            }
            verify(runtime.client).createSession(any());
            verify(runtime.client).resumeSession(anyString(), any());
            try (var missing = agents.resume(BoundAdvisor.class, "missing")) {
                assertThatThrownBy(() -> missing.agent().answer("hello").get(5, TimeUnit.SECONDS))
                        .hasRootCauseInstanceOf(CopilotConversationNotFoundException.class);
            }
            verify(runtime.client).createSession(any());
        });
    }

    @Test
    void disappearanceOfAnEstablishedConversationDoesNotResetItsHistory() {
        var runtime = new RuntimeStub();
        runner(runtime, BoundAdvisor.class).run(context -> {
            try (var instance = context.getBean(CopilotAgents.class).create(BoundAdvisor.class)) {
                instance.agent().answer("initial").get(5, TimeUnit.SECONDS);
                runtime.history.clear();
                assertThatThrownBy(() -> instance.agent().answer("continue").get(5, TimeUnit.SECONDS))
                        .hasRootCauseInstanceOf(CopilotConversationNotFoundException.class);
                verify(runtime.client).createSession(any());
            }
        });
    }

    @Test
    void closedHandleAndCachedProxyRejectFurtherWork() {
        var runtime = new RuntimeStub();
        runner(runtime, BoundAdvisor.class).run(context -> {
            var instance = context.getBean(CopilotAgents.class).create(BoundAdvisor.class);
            var proxy = instance.agent();
            String key = instance.conversationId();
            instance.close();
            instance.close();
            assertThat(instance.conversationId()).isEqualTo(key);
            assertThatThrownBy(instance::agent).hasMessage("Copilot agent instance is closed");
            assertThatThrownBy(() -> proxy.answer("hello").get(5, TimeUnit.SECONDS))
                    .hasRootCauseMessage("Copilot agent instance is closed");
            verifyNoInteractions(runtime.client);
        });
    }

    @Test
    void closeWaitsForCancelledWorkAbortAndPreservesInterruptStatus() {
        var runtime = new RuntimeStub();
        var response = new CompletableFuture<AssistantMessageEvent>();
        var abort = new CompletableFuture<Void>();
        runtime.abort = abort;
        var started = new CountDownLatch(1);
        runtime.replies = (session, prompt) -> {
            started.countDown();
            return response;
        };
        runner(runtime, BoundAdvisor.class).run(context -> {
            var instance = context.getBean(CopilotAgents.class).create(BoundAdvisor.class);
            var proxy = instance.agent();
            var interrupted = new AtomicBoolean();
            Thread closer = null;
            try {
                var result = proxy.answer("slow");
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                result.cancel(true);
                closer = Thread.ofVirtual().start(() -> {
                    Thread.currentThread().interrupt();
                    instance.close();
                    interrupted.set(Thread.currentThread().isInterrupted());
                });
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThatThrownBy(instance::agent).hasMessage("Copilot agent instance is closed"));
                verify(runtime.sessions.getFirst(), timeout(5000)).abort();
                assertThat(response.isCancelled()).isTrue();
                assertThat(closer.isAlive()).isTrue();
                verify(runtime.sessions.getFirst(), never()).close();
                assertThatThrownBy(() -> proxy.answer("new work").get())
                        .hasRootCauseMessage("Copilot agent instance is closed");
                abort.complete(null);
                closer.join(5000);
                assertThat(closer.isAlive()).isFalse();
                assertThat(interrupted.get()).isTrue();
                verify(runtime.sessions.getFirst()).close();
                verify(runtime.client, never()).close();
            } finally {
                abort.complete(null);
                response.complete(reply("done"));
                if (closer != null) {
                    closer.join(5000);
                }
                instance.close();
            }
        });
    }

    @Test
    void responseFailureStillAllowsHandleCleanup() {
        var runtime = new RuntimeStub();
        var failure = new IllegalStateException("response failed");
        runtime.replies = (session, message) -> CompletableFuture.failedFuture(failure);
        runner(runtime, BoundAdvisor.class).run(context -> {
            var instance = context.getBean(CopilotAgents.class).create(BoundAdvisor.class);
            assertThatThrownBy(() -> instance.agent().answer("hello").get(5, TimeUnit.SECONDS)).hasRootCause(failure);
            runtime.replies = (session, message) -> CompletableFuture.completedFuture(reply(message));
            assertThat(instance.agent().answer("retry").get(5, TimeUnit.SECONDS)).isEqualTo("reply:retry");
            instance.close();
            runtime.sessions.forEach(session -> verify(session).close());
            verify(runtime.client).createSession(any());
            verify(runtime.client).resumeSession(anyString(), any());
        });
    }

    @Test
    void closingInsideOwnListenerIsRejectedInsteadOfDeadlocking() {
        var runtime = new RuntimeStub();
        var instance = new AtomicReference<CopilotAgentInstance<CallbackAdvisor>>();
        CopilotAgentListener observer = (context, event) -> instance.get().close();
        runtime.replies = (session, message) -> {
            runtime.handlers.get(session).accept(reply(message));
            return CompletableFuture.completedFuture(reply(message));
        };
        runner(runtime, CallbackAdvisor.class).withBean("observer", CopilotAgentListener.class, () -> observer).run(context -> {
            try (var handle = context.getBean(CopilotAgents.class).create(CallbackAdvisor.class)) {
                instance.set(handle);
                assertThatThrownBy(() -> handle.agent().answer("hello").get(5, TimeUnit.SECONDS))
                        .hasRootCauseMessage("Cannot close a Copilot agent instance from its own invocation callback");
                verify(runtime.sessions.getFirst()).close();
            }
        });
    }

    @Test
    void invalidMessagesDoNotLeakHandleLeases() {
        var runtime = new RuntimeStub();
        runner(runtime, BoundAdvisor.class).run(context -> {
            var instance = context.getBean(CopilotAgents.class).create(BoundAdvisor.class);
            assertThatThrownBy(() -> instance.agent().answer(" ").get()).hasRootCauseInstanceOf(IllegalArgumentException.class);
            instance.close();
            verifyNoInteractions(runtime.client);
        });
    }

    @Test
    void rejectedAdmissionDoesNotMakeAnotherHandleWaitForUnrelatedWork() {
        var runtime = new RuntimeStub();
        var response = new CompletableFuture<AssistantMessageEvent>();
        var started = new CountDownLatch(1);
        runtime.replies = (session, message) -> {
            started.countDown();
            return response;
        };
        runner(runtime, BoundAdvisor.class)
                .withPropertyValues("copilot.execution.max-concurrent-invocations=1",
                        "copilot.execution.max-outstanding-invocations=1").run(context -> {
                    var agents = context.getBean(CopilotAgents.class);
                    try (var active = agents.create(BoundAdvisor.class)) {
                        var rejected = agents.create(BoundAdvisor.class);
                        try {
                            var operation = active.agent().answer("slow");
                            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                            assertThatThrownBy(() -> rejected.agent().answer("over capacity").get())
                                    .hasRootCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
                            CompletableFuture.runAsync(rejected::close).get(5, TimeUnit.SECONDS);
                            verify(runtime.sessions.getFirst(), never()).close();
                            response.complete(reply("done"));
                            operation.get(5, TimeUnit.SECONDS);
                        } finally {
                            response.complete(reply("done"));
                        }
                    }
                });
    }

    private static ApplicationContextRunner runner(RuntimeStub runtime, Class<?> marker) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CopilotAutoConfiguration.class, CopilotAgentsAutoConfiguration.class))
                .withBean(CopilotClient.class, () -> runtime.client)
                .withInitializer(context -> {
                    if (context.getBeanFactory() instanceof BeanDefinitionRegistry registry) {
                        AutoConfigurationPackages.register(registry, marker.getPackageName());
                    } else {
                        throw new IllegalStateException("Test context requires a bean definition registry");
                    }
                });
    }

    private static AssistantMessageEvent reply(String content) {
        var event = new AssistantMessageEvent();
        var data = mock(AssistantMessageEvent.AssistantMessageEventData.class);
        when(data.content()).thenReturn("reply:" + content);
        event.setData(data);
        return event;
    }

    private static final class RuntimeStub {
        private final CopilotClient client = mock(CopilotClient.class);
        private final Map<String, SessionMetadata> history = new ConcurrentHashMap<>();
        private final List<CopilotSession> sessions = new CopyOnWriteArrayList<>();
        private final Map<CopilotSession, Consumer<SessionEvent>> handlers = new ConcurrentHashMap<>();
        private volatile BiFunction<CopilotSession, String, CompletableFuture<AssistantMessageEvent>> replies =
                (session, message) -> CompletableFuture.completedFuture(reply(message));
        private volatile CompletableFuture<Void> abort = CompletableFuture.completedFuture(null);

        private RuntimeStub() {
            when(client.getSessionMetadata(anyString())).thenAnswer(invocation ->
                    CompletableFuture.completedFuture(history.get(invocation.<String>getArgument(0))));
            when(client.createSession(any())).thenAnswer(invocation -> {
                String id = invocation.<SessionConfig>getArgument(0).getSessionId();
                var metadata = new SessionMetadata();
                metadata.setSessionId(id);
                history.put(id, metadata);
                return CompletableFuture.completedFuture(attach(id));
            });
            when(client.resumeSession(anyString(), any())).thenAnswer(invocation ->
                    CompletableFuture.completedFuture(attach(invocation.getArgument(0))));
        }

        private CopilotSession attach(String id) {
            var session = mock(CopilotSession.class);
            sessions.add(session);
            when(session.getSessionId()).thenReturn(id);
            when(session.on(any())).thenAnswer(invocation -> {
                handlers.put(session, invocation.getArgument(0));
                return mock(Closeable.class);
            });
            when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation ->
                    replies.apply(session, invocation.<MessageOptions>getArgument(0).getPrompt()));
            when(session.abort()).thenAnswer(invocation -> abort);
            return session;
        }
    }
}
