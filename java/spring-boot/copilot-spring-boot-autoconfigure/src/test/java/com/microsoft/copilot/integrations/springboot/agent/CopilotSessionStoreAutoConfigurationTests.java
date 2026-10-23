package com.microsoft.copilot.integrations.springboot.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.SessionMetadata;
import com.microsoft.copilot.integrations.core.CopilotSessionStore;
import com.microsoft.copilot.integrations.springboot.agent.fixtures.TestAgent;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentInvoker;
import com.microsoft.copilot.integrations.springboot.autoconfigure.CopilotAgentsAutoConfiguration;
import com.microsoft.copilot.integrations.springboot.autoconfigure.CopilotAutoConfiguration;
import com.microsoft.copilot.integrations.springboot.autoconfigure.internal.CopilotAgentFactoryBean;
import com.microsoft.copilot.integrations.springboot.autoconfigure.internal.SpringSessionCleanup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CopilotSessionStoreAutoConfigurationTests {

    private final CopilotClient client = mock(CopilotClient.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CopilotAutoConfiguration.class, CopilotAgentsAutoConfiguration.class))
            .withBean(CopilotClient.class, () -> client)
            .withInitializer(context -> {
                if (context.getBeanFactory() instanceof BeanDefinitionRegistry registry) {
                    AutoConfigurationPackages.register(registry, TestAgent.class.getPackageName());
                }
            });

    @Test
    void registersTheStoreAndLeavesCleanupDisabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CopilotSessionStore.class)
                    .doesNotHaveBean(SpringSessionCleanup.class);
            verifyNoInteractions(client);
        });
    }

    @Test
    void mapsAgentConversationsToTheirSessionIds() {
        runner.run(context -> {
            var store = context.getBean(CopilotSessionStore.class);
            var definition = context.getBean("&test-agent", CopilotAgentFactoryBean.class).definition();

            assertThat(store.sessionId(TestAgent.class, "c1"))
                    .isEqualTo(CopilotAgentInvoker.sessionId(definition, "c1"))
                    .startsWith(CopilotAgentInvoker.MANAGED_SESSION_PREFIX);
        });
    }

    @Test
    void listsAndDeletesThroughTheSharedClient() {
        var managed = session("agent-1");
        when(client.listSessions()).thenReturn(CompletableFuture.completedFuture(List.of(managed, session("other"))));
        when(client.getSessionMetadata("agent-1")).thenReturn(CompletableFuture.completedFuture(managed));
        when(client.deleteSession("agent-1")).thenReturn(CompletableFuture.completedFuture(null));
        runner.run(context -> {
            var store = context.getBean(CopilotSessionStore.class);

            assertThat(store.list()).containsExactly(managed);
            assertThat(store.delete("agent-1")).isTrue();
            verify(client).deleteSession("agent-1");
        });
    }

    @Test
    void enablesCleanupWhenAMaxAgeIsSet() {
        runner.withPropertyValues("copilot.sessions.cleanup.max-age=1h").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SpringSessionCleanup.class);
            var cleanup = context.getBean(SpringSessionCleanup.class);
            assertThat(cleanup.isRunning()).isTrue();
            assertThat(cleanup.interval()).isEqualTo(Duration.ofMinutes(30));
            assertThat(cleanup.cleaner().maxAge()).isEqualTo(Duration.ofHours(1));
        });
        runner.withPropertyValues("copilot.sessions.cleanup.max-age=7d", "copilot.sessions.cleanup.interval=5m")
                .run(context -> assertThat(context.getBean(SpringSessionCleanup.class).interval())
                        .isEqualTo(Duration.ofMinutes(5)));
    }

    @Test
    void rejectsNonPositiveMaxAge() {
        runner.withPropertyValues("copilot.sessions.cleanup.max-age=0s").run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("copilot.sessions.cleanup.max-age must be positive"));
    }

    private static SessionMetadata session(String id) {
        var metadata = new SessionMetadata();
        metadata.setSessionId(id);
        metadata.setModifiedTime(Instant.now().toString());
        return metadata;
    }
}
