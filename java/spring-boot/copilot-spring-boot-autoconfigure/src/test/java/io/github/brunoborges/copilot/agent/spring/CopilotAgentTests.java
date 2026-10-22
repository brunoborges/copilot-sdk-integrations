package io.github.brunoborges.copilot.agent.spring;

import io.github.brunoborges.copilot.agent.*;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.SystemMessageMode;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.McpHttpServerConfig;
import com.github.copilot.rpc.McpStdioServerConfig;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.ProviderConfig;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SectionOverride;
import com.github.copilot.rpc.SectionOverrideAction;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SessionMetadata;
import com.github.copilot.rpc.SystemMessageConfig;
import com.github.copilot.rpc.SystemMessageSections;
import com.github.copilot.rpc.ToolDefinition;
import io.github.brunoborges.copilot.agent.fixtures.TestAgent;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentDefinition;
import io.github.brunoborges.copilot.autoconfigure.internal.CopilotAgentFactoryBean;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsProperties;
import io.github.brunoborges.copilot.autoconfigure.CopilotMcpProperties;
import io.github.brunoborges.copilot.autoconfigure.CopilotProviderProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CopilotAgentTests {

    private final CopilotClient client = mock(CopilotClient.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final StandardEnvironment environment = new StandardEnvironment();
    private final CopilotAgentsProperties properties = new CopilotAgentsProperties(Map.of());

    @Test
    void selectedMcpServersReachCreateAndResumeAndPropertiesOverrideAnnotationSelection() throws Exception {
        var local = new CopilotMcpProperties.Server(CopilotMcpProperties.Transport.STDIO, "node",
                List.of("server.js"), Map.of(), null, null, Map.of(), List.of("read_file"), Duration.ofSeconds(2));
        beans.registerSingleton("mcpProperties", new CopilotMcpProperties(Map.of("files", local)));
        var remote = new McpHttpServerConfig().setUrl("https://example.com/mcp").setTools(List.of("search"));
        beans.registerSingleton("remote", remote);
        var customizer = mock(CopilotAgentCustomizer.class);
        beans.registerSingleton("mcpCustomizer", customizer);
        doAnswer(invocation -> {
            var config = invocation.<SessionConfig>getArgument(1);
            assertThat(config.getMcpServers()).containsOnlyKeys("files", "remote");
            config.setMcpServers(Map.of("customized", remote));
            return null;
        }).when(customizer).customize(any(), any(SessionConfig.class));
        doAnswer(invocation -> {
            var config = invocation.<ResumeSessionConfig>getArgument(1);
            assertThat(config.getMcpServers()).containsOnlyKeys("files", "remote");
            assertThat(((McpStdioServerConfig) config.getMcpServers().get("files")).getTimeout()).isEqualTo(2000);
            config.setMcpServers(Map.of("customized", remote));
            return null;
        }).when(customizer).customize(any(), any(ResumeSessionConfig.class));
        var definition = definition(McpAgent.class, properties);
        assertThat(definition.mcpServers()).containsOnlyKeys("files", "remote");
        assertThat(definition.mcpServers().get("files")).isInstanceOf(McpStdioServerConfig.class);
        assertThat(definition.mcpServers().get("remote")).isSameAs(remote);

        var session = session("reply");
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null))
                .thenReturn(CompletableFuture.completedFuture(new SessionMetadata()));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        when(client.resumeSession(anyString(), any())).thenReturn(CompletableFuture.completedFuture(session));
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(McpAgent.class, invoker, properties);
            assertThat(agent.answer("id", "first").get(5, TimeUnit.SECONDS)).isEqualTo("reply");
            assertThat(agent.answer("id", "second").get(5, TimeUnit.SECONDS)).isEqualTo("reply");
            ArgumentCaptor<SessionConfig> created = ArgumentCaptor.captor();
            ArgumentCaptor<ResumeSessionConfig> resumed = ArgumentCaptor.captor();
            verify(client).createSession(created.capture());
            verify(client).resumeSession(anyString(), resumed.capture());
            assertThat(created.getValue().getMcpServers()).containsOnlyKeys("customized");
            assertThat(resumed.getValue().getMcpServers()).containsOnlyKeys("customized");
        }
        var override = new CopilotAgentsProperties(Map.of("mcpAgent",
                new CopilotAgentsProperties.Agent(null, null, Duration.ofSeconds(60), List.of("remote"))));
        assertThat(definition(McpAgent.class, override).mcpServers()).containsOnlyKeys("remote");
        var empty = new CopilotAgentsProperties(Map.of("mcpAgent",
                new CopilotAgentsProperties.Agent(null, null, Duration.ofSeconds(60), List.of())));
        assertThat(definition(McpAgent.class, empty).mcpServers()).isEmpty();
    }

    @Test
    void selectedProviderReachesCreateAndResumeAndPropertiesOverrideAnnotationSelection() throws Exception {
        var foundry = new CopilotProviderProperties.Provider(CopilotProviderProperties.Type.OPENAI,
                "https://example.openai.azure.com/openai/v1/", "key", null, null,
                CopilotProviderProperties.WireApi.RESPONSES, null, Map.of(), null, null);
        beans.registerSingleton("providerProperties", new CopilotProviderProperties(Map.of("foundry", foundry)));
        var local = new ProviderConfig().setType("openai").setBaseUrl("http://localhost:11434/v1");
        beans.registerSingleton("local", local);
        var definition = definition(ProviderAgent.class, properties);
        assertThat(definition.provider().getBaseUrl()).isEqualTo("https://example.openai.azure.com/openai/v1/");
        assertThat(definition.provider().getWireApi()).isEqualTo("responses");

        var session = session("reply");
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null))
                .thenReturn(CompletableFuture.completedFuture(new SessionMetadata()));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        when(client.resumeSession(anyString(), any())).thenReturn(CompletableFuture.completedFuture(session));
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(ProviderAgent.class, invoker, properties);
            assertThat(agent.answer("id", "first").get(5, TimeUnit.SECONDS)).isEqualTo("reply");
            assertThat(agent.answer("id", "second").get(5, TimeUnit.SECONDS)).isEqualTo("reply");
            ArgumentCaptor<SessionConfig> created = ArgumentCaptor.captor();
            ArgumentCaptor<ResumeSessionConfig> resumed = ArgumentCaptor.captor();
            verify(client).createSession(created.capture());
            verify(client).resumeSession(anyString(), resumed.capture());
            assertThat(created.getValue().getModel()).isEqualTo("gpt-5.2-codex");
            assertThat(created.getValue().getProvider().getApiKey()).isEqualTo("key");
            assertThat(resumed.getValue().getModel()).isEqualTo("gpt-5.2-codex");
            assertThat(resumed.getValue().getProvider().getApiKey()).isEqualTo("key");
        }
        var override = new CopilotAgentsProperties(Map.of("providerAgent",
                new CopilotAgentsProperties.Agent(null, null, Duration.ofSeconds(60), null, "local")));
        assertThat(definition(ProviderAgent.class, override).provider()).isSameAs(local);
        var none = new CopilotAgentsProperties(Map.of("providerAgent",
                new CopilotAgentsProperties.Agent(null, null, Duration.ofSeconds(60), null, "")));
        assertThat(definition(ProviderAgent.class, none).provider()).isNull();
        assertThat(definition(TestAgent.class, properties).provider()).isNull();
    }

    @Test
    void createsThenResumesConversationAndClosesEachAttachment() throws Exception {
        var session = session("hello");
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null))
                .thenReturn(CompletableFuture.completedFuture(new SessionMetadata()));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        when(client.resumeSession(anyString(), any())).thenReturn(CompletableFuture.completedFuture(session));
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(TestAgent.class, invoker, properties);
            assertThat(agent.answer("conversation", "first").get(5, TimeUnit.SECONDS)).isEqualTo("hello");
            assertThat(agent.answer("conversation", "second").get(5, TimeUnit.SECONDS)).isEqualTo("hello");
            ArgumentCaptor<SessionConfig> created = ArgumentCaptor.captor();
            verify(client).createSession(created.capture());
            ArgumentCaptor<ResumeSessionConfig> resumed = ArgumentCaptor.captor();
            verify(client).resumeSession(eq(created.getValue().getSessionId()), resumed.capture());
            assertThat(created.getValue().getSystemMessage().getMode()).isEqualTo(SystemMessageMode.APPEND);
            assertThat(resumed.getValue().getSystemMessage().getContent()).isEqualTo("Be helpful.");
            var request = mock(PermissionRequest.class);
            assertThat(created.getValue().getOnPermissionRequest().handle(request, null).join().getKind()).isEqualTo("reject");
            verify(session, times(2)).close();
        }
        verify(client, never()).close();
    }

    @Test
    void separateConversationsExecuteConcurrentlyWithDistinctSessionIds() throws Exception {
        var bothStarted = new CountDownLatch(2);
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            var session = session("reply");
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            when(session.sendAndWait(any(), anyLong())).thenAnswer(send -> {
                bothStarted.countDown();
                assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();
                return CompletableFuture.completedFuture(reply("reply"));
            });
            return CompletableFuture.completedFuture(session);
        });
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(TestAgent.class, invoker, properties);
            var first = agent.answer("one", "first");
            var second = agent.answer("two", "second");
            CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);
            ArgumentCaptor<SessionConfig> configs = ArgumentCaptor.captor();
            verify(client, times(2)).createSession(configs.capture());
            assertThat(configs.getAllValues()).extracting(SessionConfig::getSessionId).doesNotHaveDuplicates();
        }
    }

    @Test
    void cancellationAbortsTheRuntimeTurnAndSameConversationWaitsForCleanup() throws Exception {
        var started = new CountDownLatch(1);
        var firstResponse = new CompletableFuture<AssistantMessageEvent>();
        var abort = new CompletableFuture<Void>();
        var session = session("reply");
        when(session.abort()).thenReturn(abort);
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> {
            started.countDown();
            return firstResponse;
        }).thenReturn(CompletableFuture.completedFuture(reply("second reply")));
        try (var invoker = new CopilotAgentInvoker(client)) {
            try {
                var agent = agent(TestAgent.class, invoker, properties);
                var first = agent.answer("same", "first");
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(first.cancel(true)).isTrue();
                verify(session, timeout(5000)).abort();
                assertThat(firstResponse.isCancelled()).isTrue();
                var second = agent.answer("same", "second");
                Thread.sleep(50);
                verify(client).getSessionMetadata(anyString());
                verify(session, never()).close();
                abort.complete(null);
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second reply");
                verify(client, times(2)).getSessionMetadata(anyString());
                verify(session, times(2)).close();
                verify(session, times(1)).abort();
                var order = inOrder(session);
                order.verify(session).abort();
                order.verify(session).close();
            } finally {
                abort.complete(null);
                firstResponse.complete(reply("reply"));
            }
        }
    }

    @Test
    void lookupFailureDoesNotCreateOrResumeConversation() throws Exception {
        var failure = new IllegalStateException("runtime unavailable");
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.failedFuture(failure));
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(TestAgent.class, invoker, properties);
            assertThatThrownBy(() -> agent.answer("one", "hello").get(5, TimeUnit.SECONDS)).hasRootCause(failure);
            verify(client, never()).createSession(any());
            verify(client, never()).resumeSession(anyString(), any());
        }
    }

    @Test
    void responseTimeoutAndCleanupFailurePropagateWithoutClosingClient() throws Exception {
        var session = session("unused");
        var subscription = mock(Closeable.class);
        when(session.on(any())).thenReturn(subscription);
        when(session.sendAndWait(any(), anyLong())).thenReturn(
                CompletableFuture.failedFuture(new java.util.concurrent.TimeoutException("response timed out")));
        doThrow(new IOException("unsubscribe failed")).when(subscription).close();
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        var settings = new CopilotAgentsProperties(Map.of("test-agent",
                new CopilotAgentsProperties.Agent("configured-model", "high", Duration.ofMillis(25))));
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(TestAgent.class, invoker, settings);
            assertThatThrownBy(() -> agent.answer("one", "hello").get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
            verify(session).sendAndWait(any(), eq(25L));
            verify(session).close();
            verify(subscription).close();
            verify(client, never()).close();
        }
    }

    @Test
    void listenersAreSessionScopedAndFailuresAreReported() throws Exception {
        var listener = mock(CopilotAgentListener.class);
        beans.registerSingleton("listener", listener);
        var session = session("hello");
        var subscribed = mock(Closeable.class);
        when(session.on(any())).thenReturn(subscribed);
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> {
            ArgumentCaptor<Consumer<SessionEvent>> handler = ArgumentCaptor.captor();
            verify(session).on(handler.capture());
            handler.getValue().accept(reply("hello"));
            return CompletableFuture.completedFuture(reply("hello"));
        });
        doThrow(new IllegalStateException("observer failed")).when(listener).onEvent(any(), any());
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(ListenedAgent.class, invoker, properties);
            assertThatThrownBy(() -> agent.answer("customer-42", "hello").get(5, TimeUnit.SECONDS))
                    .hasRootCauseMessage("observer failed");
            ArgumentCaptor<CopilotAgentContext> context = ArgumentCaptor.captor();
            verify(listener).onEvent(context.capture(), any());
            assertThat(context.getValue().conversationId()).isEqualTo("customer-42");
            var order = inOrder(subscribed, session);
            order.verify(subscribed).close();
            order.verify(session).close();
        }
    }

    @Test
    void customizationBindingsAndExternalSettingsApplyToCreateAndResume() throws Exception {
        var transform = new SectionOverride().setTransform(text -> CompletableFuture.completedFuture(text + " enhanced"));
        var system = new SystemMessageConfig().setMode(SystemMessageMode.CUSTOMIZE)
                .setSections(Map.of(SystemMessageSections.TONE, transform)).setContent("Extra.");
        var tool = mock(ToolDefinition.class);
        when(tool.name()).thenReturn("tool");
        PermissionHandler permissions = (request, invocation) -> CompletableFuture.completedFuture(PermissionRequestResult.reject("deny"));
        var customizer = mock(CopilotAgentCustomizer.class);
        beans.registerSingleton("system", system);
        beans.registerSingleton("tool", tool);
        beans.registerSingleton("permissions", permissions);
        beans.registerSingleton("customizer", customizer);
        var definition = definition(CustomizedAgent.class, properties);
        assertThat(definition.systemMessage()).isSameAs(system);
        assertThat(definition.systemMessage().getSections().get(SystemMessageSections.TONE).getTransform()
                .apply("original").join()).isEqualTo("original enhanced");
        assertThat(definition.tools()).containsExactly(tool);
        assertThat(definition.permissions()).isSameAs(permissions);
        assertThat(definition.customizers()).containsExactly(customizer);
        var session = session("customized");
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null))
                .thenReturn(CompletableFuture.completedFuture(new SessionMetadata()));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        when(client.resumeSession(anyString(), any())).thenReturn(CompletableFuture.completedFuture(session));
        var settings = new CopilotAgentsProperties(Map.of("customizedAgent",
                new CopilotAgentsProperties.Agent("external-model", "high", Duration.ofSeconds(5))));
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(CustomizedAgent.class, invoker, settings);
            agent.answer("same", "first").get(5, TimeUnit.SECONDS);
            agent.answer("same", "second").get(5, TimeUnit.SECONDS);
            ArgumentCaptor<SessionConfig> created = ArgumentCaptor.captor();
            ArgumentCaptor<ResumeSessionConfig> resumed = ArgumentCaptor.captor();
            verify(client).createSession(created.capture());
            verify(client).resumeSession(anyString(), resumed.capture());
            assertThat(created.getValue().getSystemMessage()).isSameAs(system);
            assertThat(resumed.getValue().getSystemMessage()).isSameAs(system);
            assertThat(created.getValue().getTools()).extracting(ToolDefinition::name).containsExactly("tool");
            assertThat(resumed.getValue().getTools()).extracting(ToolDefinition::name).containsExactly("tool");
            assertThat(created.getValue().getModel()).isEqualTo("external-model");
            assertThat(resumed.getValue().getReasoningEffort()).isEqualTo("high");
            verify(customizer).customize(any(), eq(created.getValue()));
            verify(customizer).customize(any(), eq(resumed.getValue()));
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(invoker, "pending"))
                    .isEqualTo(Map.of());
        }
    }

    @Test
    void registrationFailureAndMissingReplyBothCloseTheirSessions() throws Exception {
        var session = session("unused");
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        var registrationFailure = new IllegalStateException("registration failed");
        when(session.on(any())).thenThrow(registrationFailure);
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(TestAgent.class, invoker, properties);
            assertThatThrownBy(() -> agent.answer("one", "hello").get(5, TimeUnit.SECONDS))
                    .hasRootCause(registrationFailure);
            verify(session).close();
            doReturn(mock(Closeable.class)).when(session).on(any());
            when(session.sendAndWait(any(), anyLong())).thenReturn(CompletableFuture.completedFuture(null));
            assertThatThrownBy(() -> agent.answer("one", "hello").get(5, TimeUnit.SECONDS))
                    .hasRootCauseMessage("Copilot agent completed without an assistant text response");
            verify(session, times(2)).close();
        }
    }

    @Test
    void closedInfrastructureRejectsFurtherInvocations() throws Exception {
        var invoker = new CopilotAgentInvoker(client);
        var agent = agent(TestAgent.class, invoker, properties);
        invoker.close();
        assertThatThrownBy(() -> agent.answer("one", "hello").get())
                .hasRootCauseMessage("Copilot agent infrastructure is closed");
        verifyNoInteractions(client);
    }
    @Test
    void instructionsCanBeReplacedOrLoadedFromResource() {
        assertThat(definition(ReplaceAgent.class, properties).systemMessage().getMode()).isEqualTo(SystemMessageMode.REPLACE);
        assertThat(definition(ResourceAgent.class, properties).systemMessage().getContent())
                .contains("concise Java examples");
    }

    @Test
    void invalidContractsAndConflictingInstructionsFailAtInitialization() {
        try (var invoker = new CopilotAgentInvoker(client)) {
            for (Class<?> invalid : List.of(WrongReturn.class, MissingId.class, BothAnnotations.class, ConflictingInstructions.class,
                    MissingResource.class, EmptyReplacement.class, CustomizeWithoutBean.class, MissingPermissionBean.class,
                    MixedConversationForms.class)) {
                assertThatThrownBy(() -> agent(invalid, invoker, properties)).as(invalid.getSimpleName())
                        .isInstanceOf(RuntimeException.class);
            }
        }
        verifyNoInteractions(client);
    }

    @Test
    void objectMethodsAndInvalidArgumentsDoNotTouchSdk() throws Exception {
        try (var invoker = new CopilotAgentInvoker(client)) {
            var agent = agent(TestAgent.class, invoker, properties);
            assertThat(agent.toString()).isEqualTo("CopilotAgent[test-agent]");
            assertThat(agent.equals(agent)).isTrue();
            assertThat(agent.equals(new Object())).isFalse();
            assertThat(agent.hashCode()).isEqualTo(System.identityHashCode(agent));
            assertThatThrownBy(() -> agent.answer("", "hello").get()).hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> agent.answer("id", null).get()).hasRootCauseInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(client);
        }
    }

    @Test
    void sessionIdentityIsStableAndScopedToAgent() {
        var first = definition(TestAgent.class, properties);
        var second = definition(ReplaceAgent.class, properties);
        assertThat(CopilotAgentInvoker.sessionId(first, "customer"))
                .isEqualTo(CopilotAgentInvoker.sessionId(first, "customer"))
                .isNotEqualTo(CopilotAgentInvoker.sessionId(first, "another"))
                .isNotEqualTo(CopilotAgentInvoker.sessionId(second, "customer"))
                .doesNotContain("customer");
    }

    private CopilotAgentDefinition definition(Class<?> type, CopilotAgentsProperties settings) {
        return SpringTestSupport.definition(type, beans, environment, new DefaultResourceLoader(), settings);
    }

    private <T> T agent(Class<T> type, CopilotAgentInvoker invoker, CopilotAgentsProperties settings) {
        var factory = new CopilotAgentFactoryBean(type, invoker, beans, environment, new DefaultResourceLoader(), settings);
        factory.afterPropertiesSet();
        return type.cast(factory.getObject());
    }

    private static CopilotSession session(String text) {
        var session = mock(CopilotSession.class);
        var response = reply(text);
        when(session.on(any())).thenReturn(mock(Closeable.class));
        when(session.sendAndWait(any(), anyLong())).thenReturn(CompletableFuture.completedFuture(response));
        return session;
    }

    private static AssistantMessageEvent reply(String content) {
        var event = new AssistantMessageEvent();
        var data = mock(AssistantMessageEvent.AssistantMessageEventData.class);
        when(data.content()).thenReturn(content);
        event.setData(data);
        return event;
    }

    @CopilotAgent(listenerRefs = "listener")
    public interface ListenedAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }
    @CopilotAgent(model = "gpt-5.2-codex", provider = "foundry")
    public interface ProviderAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }
    @CopilotAgent(mcpServers = {"files", "remote"}, customizerRefs = "mcpCustomizer")
    public interface McpAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }
    @CopilotAgent(systemMessageRef = "system", permissionHandlerRef = "permissions", toolRefs = "tool", customizerRefs = "customizer")
    public interface CustomizedAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }
    @CopilotAgent(instructions = "Replacement.", instructionsMode = SystemMessageMode.REPLACE)
    public interface ReplaceAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }
    @CopilotAgent(instructionsResource = "classpath:agent-instructions.txt")
    public interface ResourceAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }
    @CopilotAgent
    public interface WrongReturn {
        void answer(@ConversationId String id, @UserMessage String message);
    }
    @CopilotAgent
    public interface MissingId {
        CompletableFuture<String> answer(String id, @UserMessage String message);
    }
    @CopilotAgent
    public interface BothAnnotations {
        CompletableFuture<String> answer(@ConversationId @UserMessage String id, @UserMessage String message);
    }
    @CopilotAgent(instructions = "one", instructionsResource = "classpath:agent-instructions.txt")
    public interface ConflictingInstructions {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }
    @CopilotAgent(instructionsResource = "classpath:missing-instructions.txt")
    public interface MissingResource {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }
    @CopilotAgent(instructionsMode = SystemMessageMode.REPLACE)
    public interface EmptyReplacement {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }
    @CopilotAgent(instructionsMode = SystemMessageMode.CUSTOMIZE)
    public interface CustomizeWithoutBean {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }
    @CopilotAgent(permissionHandlerRef = "absent")
    public interface MissingPermissionBean {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }
    @CopilotAgent
    public interface MixedConversationForms {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
        CompletableFuture<String> answer(@UserMessage String message);
    }
}
