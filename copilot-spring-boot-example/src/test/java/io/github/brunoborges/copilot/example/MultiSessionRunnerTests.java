package io.github.brunoborges.copilot.example;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.github.copilot.CopilotClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class MultiSessionRunnerTests {

    private final ConversationService conversations = mock(ConversationService.class);
    private final SpringAdvisor advisor = mock(SpringAdvisor.class);
    private final MultiSessionRunner runner = new MultiSessionRunner(conversations, advisor);

    @Test
    void helpDoesNotCreateConversations() throws Exception {
        runner.run(new DefaultApplicationArguments("--help"));
        verifyNoInteractions(conversations, advisor);
    }

    @Test
    void usesDefaultPrompts() throws Exception {
        runner.run(new DefaultApplicationArguments());
        verify(conversations).converse(eq("conversation-1"), contains("dependency injection"));
        verify(conversations).converse(eq("conversation-2"), contains("virtual threads"));
    }

    @Test
    void customPromptsRemainAssociatedWithTheirConversationIds() throws Exception {
        runner.run(new DefaultApplicationArguments("first prompt", "second prompt", "--copilot.cli-path=/bin/copilot"));
        verify(conversations).converse("conversation-1", "first prompt");
        verify(conversations).converse("conversation-2", "second prompt");
    }

    @Test
    void typedAgentModeUsesSeparateConversationsAndResumesTheFirstForFollowUp() throws Exception {
        when(advisor.answer(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture("answer"));
        runner.run(new DefaultApplicationArguments("--agent", "first prompt", "second prompt"));
        org.mockito.ArgumentCaptor<String> ids = org.mockito.ArgumentCaptor.captor();
        verify(advisor, times(3)).answer(ids.capture(), anyString());
        assertThat(ids.getAllValues().get(0)).isEqualTo(ids.getAllValues().get(2))
                .isNotEqualTo(ids.getAllValues().get(1));
        verifyNoInteractions(conversations);
    }

    @Test
    void runsBothConversationsConcurrently() throws Exception {
        var started = new CountDownLatch(2);
        doAnswer(invocation -> {
            assertThat(Thread.currentThread().isVirtual()).isTrue();
            started.countDown();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(conversations).converse(anyString(), anyString());
        runner.run(new DefaultApplicationArguments());
        assertThat(started.getCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void rejectsAnIncompletePromptPairBeforeCreatingConversations(int count) {
        var prompts = new String[count];
        java.util.Arrays.fill(prompts, "prompt");
        assertThatIllegalArgumentException().isThrownBy(() -> runner.run(new DefaultApplicationArguments(prompts)))
                .withMessageContaining("exactly two");
        verifyNoInteractions(conversations);
    }

    @Test
    void conversationFailureFailsTheRunner() throws Exception {
        var failure = new IOException("conversation failed");
        doThrow(failure).when(conversations).converse(eq("conversation-1"), anyString());
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments())).hasRootCause(failure);
        verify(conversations).converse(eq("conversation-2"), anyString());
    }

    @Test
    void preservesInterruption() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void springWiresTheExampleWithoutRunningConversationsDuringContextCreation() {
        var client = mock(CopilotClient.class);
        new ApplicationContextRunner().withUserConfiguration(MultiSessionApplication.class)
                .withBean(CopilotClient.class, () -> client).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ConversationService.class)
                            .hasSingleBean(ConversationOutput.class).hasSingleBean(DenyPermissions.class)
                            .hasSingleBean(MultiSessionRunner.class);
                    assertThat(context).hasSingleBean(SpringAdvisor.class);
                    verifyNoInteractions(client);
                });
        verify(client).close();
    }
}
