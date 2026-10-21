package io.github.brunoborges.copilot.example;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.SessionConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ConversationServiceTests {

    private final CopilotClient client = mock(CopilotClient.class);
    private final ConversationOutput output = mock(ConversationOutput.class);
    private final PermissionHandler permissions = new DenyPermissions();
    private final ConversationService service = new ConversationService(client, output, permissions);
    private final CopilotSession session = mock(CopilotSession.class);
    private final Closeable subscription = mock(Closeable.class);

    @Test
    void routesInterleavedEventsToTheirOwnConversations() throws Exception {
        var otherSession = mock(CopilotSession.class);
        var otherSubscription = mock(Closeable.class);
        when(client.createSession(any(SessionConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(session))
                .thenReturn(CompletableFuture.completedFuture(otherSession));
        when(session.getSessionId()).thenReturn("sdk-1");
        when(otherSession.getSessionId()).thenReturn("sdk-2");
        when(session.on(eq(AssistantMessageEvent.class), any())).thenReturn(subscription);
        when(otherSession.on(eq(AssistantMessageEvent.class), any())).thenReturn(otherSubscription);
        var firstResponse = new CompletableFuture<AssistantMessageEvent>();
        var secondResponse = new CompletableFuture<AssistantMessageEvent>();
        var firstWaiting = new CountDownLatch(1);
        var secondWaiting = new CountDownLatch(1);
        when(session.sendAndWait(any(MessageOptions.class))).thenAnswer(invocation -> {
            firstWaiting.countDown();
            return firstResponse;
        });
        when(otherSession.sendAndWait(any(MessageOptions.class))).thenAnswer(invocation -> {
            secondWaiting.countDown();
            return secondResponse;
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var first = executor.submit(() -> {
                    service.converse("first-chat", "first prompt");
                    return null;
                });
                assertThat(firstWaiting.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    service.converse("second-chat", "second prompt");
                    return null;
                });
                assertThat(secondWaiting.await(5, TimeUnit.SECONDS)).isTrue();

                ArgumentCaptor<Consumer<AssistantMessageEvent>> firstHandler = ArgumentCaptor.captor();
                ArgumentCaptor<Consumer<AssistantMessageEvent>> secondHandler = ArgumentCaptor.captor();
                verify(session).on(eq(AssistantMessageEvent.class), firstHandler.capture());
                verify(otherSession).on(eq(AssistantMessageEvent.class), secondHandler.capture());
                var firstMessage = mock(AssistantMessageEvent.class);
                var secondMessage = mock(AssistantMessageEvent.class);
                secondHandler.getValue().accept(secondMessage);
                firstHandler.getValue().accept(firstMessage);
                verify(output).onMessage("second-chat", "sdk-2", secondMessage);
                verify(output).onMessage("first-chat", "sdk-1", firstMessage);
                verifyNoMoreInteractions(output);

                firstResponse.complete(firstMessage);
                first.get(5, TimeUnit.SECONDS);
                verify(subscription).close();
                verify(session).close();
                verify(otherSession, never()).close();

                secondResponse.complete(secondMessage);
                second.get(5, TimeUnit.SECONDS);
                verify(otherSubscription).close();
                verify(otherSession).close();
            } finally {
                firstResponse.complete(null);
                secondResponse.complete(null);
            }
        }
        ArgumentCaptor<SessionConfig> configs = ArgumentCaptor.captor();
        verify(client, times(2)).createSession(configs.capture());
        assertThat(configs.getAllValues().getFirst()).isNotSameAs(configs.getAllValues().getLast());
        assertThat(configs.getAllValues()).allSatisfy(config ->
                assertThat(config.getOnPermissionRequest()).isSameAs(permissions));
        verify(client, never()).close();
    }

    @Test
    void registersBeforeSendingAndUnsubscribesBeforeClosingSession() throws Exception {
        configureSession(CompletableFuture.completedFuture(null));
        service.converse("chat", "hello");

        var order = inOrder(session, subscription);
        order.verify(session).on(eq(AssistantMessageEvent.class), any());
        ArgumentCaptor<MessageOptions> message = ArgumentCaptor.captor();
        order.verify(session).sendAndWait(message.capture());
        assertThat(message.getValue().getPrompt()).isEqualTo("hello");
        order.verify(subscription).close();
        order.verify(session).close();
        verify(client, never()).close();
    }

    @Test
    void responseFailureCleansUpAndPropagates() throws Exception {
        var failure = new IllegalStateException("response failed");
        configureSession(CompletableFuture.failedFuture(failure));
        assertThatThrownBy(() -> service.converse("chat", "hello")).hasCause(failure);
        verify(subscription).close();
        verify(session).close();
        verify(client, never()).close();
    }

    @Test
    void registrationFailureClosesTheNewSession() {
        when(client.createSession(any(SessionConfig.class))).thenReturn(CompletableFuture.completedFuture(session));
        var failure = new IllegalStateException("listener registration failed");
        when(session.on(eq(AssistantMessageEvent.class), any())).thenThrow(failure);
        assertThatThrownBy(() -> service.converse("chat", "hello")).isSameAs(failure);
        verify(session).close();
        verify(session, never()).sendAndWait(any(MessageOptions.class));
        verify(client, never()).close();
    }

    @Test
    void creationFailureDoesNotCloseTheSharedClient() {
        var failure = new IllegalStateException("creation failed");
        when(client.createSession(any(SessionConfig.class))).thenReturn(CompletableFuture.failedFuture(failure));
        assertThatThrownBy(() -> service.converse("chat", "hello")).hasCause(failure);
        verifyNoInteractions(session, subscription, output);
        verify(client, never()).close();
    }

    @Test
    void synchronousSendFailureAlsoCleansUp() throws Exception {
        when(client.createSession(any(SessionConfig.class))).thenReturn(CompletableFuture.completedFuture(session));
        when(session.on(eq(AssistantMessageEvent.class), any())).thenReturn(subscription);
        var failure = new IllegalStateException("send failed");
        when(session.sendAndWait(any(MessageOptions.class))).thenThrow(failure);
        assertThatThrownBy(() -> service.converse("chat", "hello")).isSameAs(failure);
        verify(subscription).close();
        verify(session).close();
    }

    @Test
    void unsubscribeFailureStillClosesTheSession() throws Exception {
        configureSession(CompletableFuture.completedFuture(null));
        var failure = new IOException("unsubscribe failed");
        doThrow(failure).when(subscription).close();
        assertThatThrownBy(() -> service.converse("chat", "hello")).isSameAs(failure);
        verify(session).close();
        verify(client, never()).close();
    }

    @Test
    void cleanupFailuresDoNotHideTheResponseFailure() throws Exception {
        var responseFailure = new IllegalStateException("response failed");
        var unsubscribeFailure = new IOException("unsubscribe failed");
        var closeFailure = new IllegalStateException("detach failed");
        configureSession(CompletableFuture.failedFuture(responseFailure));
        doThrow(unsubscribeFailure).when(subscription).close();
        doThrow(closeFailure).when(session).close();
        assertThatThrownBy(() -> service.converse("chat", "hello"))
                .isInstanceOf(CompletionException.class).hasCause(responseFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(unsubscribeFailure, closeFailure));
        verify(client, never()).close();
    }

    private void configureSession(CompletableFuture<AssistantMessageEvent> response) {
        when(client.createSession(any(SessionConfig.class))).thenReturn(CompletableFuture.completedFuture(session));
        when(session.on(eq(AssistantMessageEvent.class), any())).thenReturn(subscription);
        when(session.sendAndWait(any(MessageOptions.class))).thenReturn(response);
    }
}
