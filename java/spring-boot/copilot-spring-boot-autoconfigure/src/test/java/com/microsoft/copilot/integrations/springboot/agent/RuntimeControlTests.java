package com.microsoft.copilot.integrations.springboot.agent;

import com.microsoft.copilot.integrations.core.*;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.rpc.GitHubTokenProviderResult;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SessionMetadata;
import com.github.copilot.rpc.ToolInvocation;
import com.microsoft.copilot.integrations.springboot.agent.fixtures.TestAgent;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentDefinition;
import com.microsoft.copilot.integrations.springboot.autoconfigure.internal.CopilotAgentFactoryBean;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentInvoker;
import com.microsoft.copilot.integrations.springboot.agent.tools.SecurityAwareTools;
import com.microsoft.copilot.integrations.core.internal.CopilotAgentExecutionLimits;
import com.microsoft.copilot.integrations.springboot.autoconfigure.CopilotAgentsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.github.copilot.rpc.PreToolUseHookInput;
import com.github.copilot.rpc.PreToolUseHookOutput;
import org.slf4j.MDC;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuntimeControlTests {

    private final CopilotClient client = mock(CopilotClient.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final StandardEnvironment environment = new StandardEnvironment();
    private final CopilotAgentsProperties properties = new CopilotAgentsProperties(Map.of());
    private final RecordingTelemetry telemetry = new RecordingTelemetry();

    @AfterEach
    void clearCallerContext() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    // --- cancellation -------------------------------------------------------------------------------------

    @Test
    void cancellationWhileOpeningSessionSkipsTheTurnAndReleasesCapacityOnce() throws Exception {
        var opening = new CompletableFuture<CopilotSession>();
        var openingStarted = new CountDownLatch(1);
        var session = session(reply("unused"));
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            openingStarted.countDown();
            return opening;
        }).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        try (var invoker = invoker()) {
            var agent = agent(TestAgent.class, invoker);
            var first = agent.answer("one", "hello");
            assertThat(openingStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(first.cancel(true)).isTrue();
            // Cancellation finishes the invocation without waiting for the in-flight session to open.
            awaitStopped(1);
            assertThat(telemetry.events).containsExactly("cancelled", "stop");
            verify(session, never()).close();
            opening.complete(session);
            verify(session, timeout(5000)).close();
            verify(session, never()).sendAndWait(any(), anyLong());
            verify(session, never()).abort();
            assertThat(telemetry.events).containsExactly("cancelled", "stop");
            assertCapacityReleasedOnce(agent, session);
        }
    }

    @Test
    void cancellationReleasesCapacityWhenSessionLookupNeverCompletes() throws Exception {
        var lookupStarted = new CountDownLatch(1);
        var session = session(reply("unused"));
        when(client.getSessionMetadata(anyString())).thenAnswer(invocation -> {
            lookupStarted.countDown();
            return new CompletableFuture<SessionMetadata>();
        }).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        try (var invoker = invoker()) {
            var agent = agent(TestAgent.class, invoker);
            var first = agent.answer("one", "hello");
            assertThat(lookupStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(first.cancel(true)).isTrue();
            awaitStopped(1);
            verify(client, never()).createSession(any());
            assertCapacityReleasedOnce(agent, session);
        }
    }

    @Test
    void cancellationDuringTheTurnAbortsTheSessionBeforeClosingIt() throws Exception {
        var response = new CompletableFuture<AssistantMessageEvent>();
        var sent = new CountDownLatch(1);
        var session = session(reply("unused"));
        newSessions(session);
        when(session.abort()).thenReturn(CompletableFuture.completedFuture(null));
        var next = CompletableFuture.completedFuture(reply("next"));
        when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> {
            sent.countDown();
            return response;
        }).thenReturn(next);
        try (var invoker = invoker()) {
            var agent = agent(TestAgent.class, invoker);
            var first = agent.answer("one", "hello");
            assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
            first.cancel(true);
            verify(session, timeout(5000)).close();
            var order = org.mockito.Mockito.inOrder(session);
            order.verify(session).abort();
            order.verify(session).close();
            assertThat(response.isCancelled()).isTrue();
            assertThatThrownBy(first::join).isInstanceOf(java.util.concurrent.CancellationException.class);
            awaitStopped(1);
            assertThat(telemetry.events).containsExactly("cancelled", "stop");
            assertCapacityReleasedOnce(agent, session);
        }
    }

    @Test
    void abortFailuresAreLoggedAndDoNotLeakCapacity() throws Exception {
        var response = new CompletableFuture<AssistantMessageEvent>();
        var sent = new CountDownLatch(1);
        var session = session(reply("unused"));
        newSessions(session);
        when(session.abort()).thenThrow(new IllegalStateException("session terminated"))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("abort rejected")));
        when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> {
            sent.countDown();
            return response;
        });
        try (var invoker = invoker()) {
            var agent = agent(TestAgent.class, invoker);
            for (int attempt = 0; attempt < 2; attempt++) {
                var current = new CountDownLatch(1);
                var pending = new CompletableFuture<AssistantMessageEvent>();
                when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> {
                    current.countDown();
                    return pending;
                });
                var result = agent.answer("conversation-" + attempt, "hello");
                assertThat(current.await(5, TimeUnit.SECONDS)).isTrue();
                result.cancel(true);
                verify(session, timeout(5000).times(attempt + 1)).close();
                awaitStopped(attempt + 1);
            }
            verify(session, org.mockito.Mockito.times(2)).abort();
            assertThat(telemetry.events).containsExactly("cancelled", "stop", "cancelled", "stop");
            var next = CompletableFuture.completedFuture(reply("next"));
            when(session.sendAndWait(any(), anyLong())).thenReturn(next);
            assertThat(agent.answer("after", "hello").get(5, TimeUnit.SECONDS)).isEqualTo("next");
        }
    }

    @Test
    void cancellationAfterCompletionIsANoOp() throws Exception {
        var session = session(reply("done"));
        newSessions(session);
        try (var invoker = invoker()) {
            var agent = agent(TestAgent.class, invoker);
            var result = agent.answer("one", "hello");
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("done");
            assertThat(result.cancel(true)).isFalse();
            verify(session, never()).abort();
            awaitStopped(1);
            assertThat(telemetry.events).containsExactly("stop");
        }
    }

    @Test
    void responseTimeoutAbortsTheRuntimeTurn() throws Exception {
        var session = session(null);
        newSessions(session);
        when(session.abort()).thenReturn(CompletableFuture.completedFuture(null));
        when(session.sendAndWait(any(), anyLong()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("sendAndWait timed out")));
        try (var invoker = invoker()) {
            var agent = agent(TestAgent.class, invoker);
            assertThatThrownBy(() -> agent.answer("one", "hello").get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(TimeoutException.class);
            var order = org.mockito.Mockito.inOrder(session);
            order.verify(session).abort();
            order.verify(session).close();
            awaitStopped(1);
            assertThat(telemetry.events).containsExactly("error:TimeoutException", "stop");
        }
    }

    // --- per-user authentication ---------------------------------------------------------------------------

    @Test
    void globalTokenResolverRunsOnTheCallerThreadAndIsolatesSessionsPerCaller() throws Exception {
        var resolverThread = new AtomicReference<Thread>();
        var contexts = new CopyOnWriteArrayList<CopilotAgentContext>();
        beans.registerSingleton("tokens", new CopilotTokenResolver() {
            @Override
            public String resolveToken(CopilotAgentContext context) {
                resolverThread.set(Thread.currentThread());
                contexts.add(context);
                return "user-token-" + currentUser();
            }

            @Override
            public String partitionKey(String agentName, String conversationId) {
                return currentUser();
            }
        });
        var session = session(reply("reply"));
        var known = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        when(client.getSessionMetadata(anyString())).thenAnswer(invocation -> CompletableFuture.completedFuture(
                known.contains(invocation.<String>getArgument(0)) ? new SessionMetadata() : null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            String id = invocation.<SessionConfig>getArgument(0).getSessionId();
            known.add(id);
            when(session.getSessionId()).thenReturn(id);
            return CompletableFuture.completedFuture(session);
        });
        when(client.resumeSession(anyString(), any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<String>getArgument(0));
            return CompletableFuture.completedFuture(session);
        });
        try (var invoker = invoker()) {
            var agent = agent(TestAgent.class, invoker);
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
            agent.answer("one", "first").get(5, TimeUnit.SECONDS);
            assertThat(resolverThread.get()).isSameAs(Thread.currentThread());
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("bob", "n/a", "ROLE_USER"));
            agent.answer("one", "second").get(5, TimeUnit.SECONDS);
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
            agent.answer("one", "third").get(5, TimeUnit.SECONDS);
            ArgumentCaptor<SessionConfig> created = ArgumentCaptor.captor();
            ArgumentCaptor<String> resumedId = ArgumentCaptor.captor();
            ArgumentCaptor<ResumeSessionConfig> resumed = ArgumentCaptor.captor();
            verify(client, org.mockito.Mockito.times(2)).createSession(created.capture());
            verify(client).resumeSession(resumedId.capture(), resumed.capture());
            assertThat(created.getAllValues()).extracting(SessionConfig::getGitHubToken)
                    .containsExactly("user-token-alice", "user-token-bob");
            var aliceSession = created.getAllValues().get(0).getSessionId();
            assertThat(created.getAllValues().get(1).getSessionId()).isNotEqualTo(aliceSession);
            assertThat(resumedId.getValue()).isEqualTo(aliceSession);
            assertThat(resumed.getValue().getGitHubToken()).isEqualTo("user-token-alice");
            assertThat(contexts).extracting(CopilotAgentContext::agentName).containsOnly("test-agent");
            assertThat(contexts).extracting(CopilotAgentContext::conversationId).containsOnly("one");
            assertThat(contexts).extracting(CopilotAgentContext::sessionId).startsWith(aliceSession);
        }
    }

    @Test
    void tokenWithoutPartitionKeyFailsClosedBeforeSdkWork() throws Exception {
        beans.registerSingleton("tokens", (CopilotTokenResolver) context -> "user-token");
        try (var invoker = invoker()) {
            var result = agent(TestAgent.class, invoker).answer("one", "hello");
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).cause()
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("partition key");
            verifyNoInteractions(client);
        }
    }

    @Test
    void sharedPartitionKeepsTheUnpartitionedSessionId() throws Exception {
        beans.registerSingleton("tokens", shared(context -> "user-token"));
        var session = session(reply("reply"));
        newSessions(session);
        try (var invoker = invoker()) {
            agent(TestAgent.class, invoker).answer("one", "hello").get(5, TimeUnit.SECONDS);
            ArgumentCaptor<SessionConfig> created = ArgumentCaptor.captor();
            verify(client).createSession(created.capture());
            assertThat(created.getValue().getSessionId())
                    .isEqualTo(CopilotAgentInvoker.sessionId(definition(TestAgent.class), "one"));
        }
    }

    private static CopilotTokenResolver shared(CopilotTokenResolver tokens) {
        return new CopilotTokenResolver() {
            @Override
            public String resolveToken(CopilotAgentContext context) {
                return tokens.resolveToken(context);
            }

            @Override
            public String partitionKey(String agentName, String conversationId) {
                return SHARED;
            }
        };
    }

    @Test
    void nullTokenKeepsAmbientAuthentication() throws Exception {
        beans.registerSingleton("tokens", (CopilotTokenResolver) context -> null);
        var session = session(reply("reply"));
        newSessions(session);
        try (var invoker = invoker()) {
            agent(TestAgent.class, invoker).answer("one", "hello").get(5, TimeUnit.SECONDS);
            ArgumentCaptor<SessionConfig> created = ArgumentCaptor.captor();
            verify(client).createSession(created.capture());
            assertThat(created.getValue().getGitHubToken()).isNull();
            assertThat(created.getValue().getGitHubTokenProvider()).isNull();
        }
    }

    @Test
    void perAgentResolverWinsAndAmbiguousGlobalSelectionFailsStartup() throws Exception {
        beans.registerSingleton("first", shared(context -> "first-token"));
        beans.registerSingleton("second", shared(context -> "second-token"));
        assertThatThrownBy(() -> definition(TestAgent.class)).isInstanceOf(NoUniqueBeanDefinitionException.class);
        assertThat(definition(SecondTokenAgent.class).tokenResolver()).isSameAs(beans.getBean("second"));
        var session = session(reply("reply"));
        newSessions(session);
        try (var invoker = invoker()) {
            agent(SecondTokenAgent.class, invoker).answer("one", "hello").get(5, TimeUnit.SECONDS);
            ArgumentCaptor<SessionConfig> created = ArgumentCaptor.captor();
            verify(client).createSession(created.capture());
            assertThat(created.getValue().getGitHubToken()).isEqualTo("second-token");
        }
    }

    @Test
    void customizerTokenProviderIsNotOverridden() throws Exception {
        beans.registerSingleton("tokens", shared(context -> "resolved"));
        beans.registerSingleton("rotating", (CopilotAgentCustomizer) new CopilotAgentCustomizer() {
            @Override
            public void customize(CopilotAgentContext context, SessionConfig config) {
                config.setGitHubTokenProvider(args -> CompletableFuture.completedFuture(
                        GitHubTokenProviderResult.token("rotating", 3600)));
            }
        });
        var session = session(reply("reply"));
        newSessions(session);
        try (var invoker = invoker()) {
            agent(RotatingTokenAgent.class, invoker).answer("one", "hello").get(5, TimeUnit.SECONDS);
            ArgumentCaptor<SessionConfig> created = ArgumentCaptor.captor();
            verify(client).createSession(created.capture());
            assertThat(created.getValue().getGitHubToken()).isNull();
            assertThat(created.getValue().getGitHubTokenProvider()).isNotNull();
        }
    }

    @Test
    void resolverFailureFailsTheInvocationBeforeSdkWork() throws Exception {
        var failure = new IllegalStateException("no user");
        beans.registerSingleton("tokens", (CopilotTokenResolver) context -> {
            throw failure;
        });
        try (var invoker = invoker()) {
            var result = agent(TestAgent.class, invoker).answer("one", "hello");
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCause(failure);
            verifyNoInteractions(client);
            assertThat(telemetry.events).isEmpty();
        }
    }

    // --- context propagation ------------------------------------------------------------------------------

    @Test
    void callerSecurityContextAndMdcReachToolsPermissionsAndListenersOnSdkThreads() throws Exception {
        beans.registerSingleton("securityTools", new SecurityAwareTools());
        var seen = new CopyOnWriteArrayList<String>();
        beans.registerSingleton("permissions", (PermissionHandler) (request, invocation) -> {
            seen.add("permission:" + currentUser() + "/" + MDC.get("requestId"));
            return CompletableFuture.completedFuture(PermissionRequestResult.reject("no"));
        });
        beans.registerSingleton("listener", (CopilotAgentListener) (context, event) ->
                seen.add("listener:" + currentUser() + "/" + MDC.get("requestId")));
        var session = session(null);
        var handler = new AtomicReference<Consumer<SessionEvent>>();
        when(session.on(any())).thenAnswer(invocation -> {
            handler.set(invocation.getArgument(0));
            return mock(Closeable.class);
        });
        var config = new AtomicReference<SessionConfig>();
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            config.set(invocation.getArgument(0));
            when(session.getSessionId()).thenReturn(config.get().getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        var afterCallback = new CopyOnWriteArrayList<String>();
        when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> {
            var response = new CompletableFuture<AssistantMessageEvent>();
            // Simulates the SDK delivering callbacks on its own thread.
            Thread.ofPlatform().start(() -> {
                try {
                    var tool = config.get().getTools().getFirst();
                    String output = (String) tool.handler().invoke(new ToolInvocation().setArguments(
                            new ObjectMapper().valueToTree(Map.of("prefix", "user=")))).join();
                    config.get().getOnPermissionRequest().handle(new PermissionRequest(), null).join();
                    handler.get().accept(new SessionIdleEvent());
                    afterCallback.add(currentUser() + "/" + MDC.get("requestId"));
                    response.complete(reply(output));
                } catch (Throwable ex) {
                    response.completeExceptionally(ex);
                }
            });
            return response;
        });
        try (var invoker = SpringTestSupport.invoker(client)) {
            var agent = agent(SecuredToolAgent.class, invoker);
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
            MDC.put("requestId", "r-42");
            var result = agent.answer("one", "who am I?");
            SecurityContextHolder.clearContext();
            MDC.clear();
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("user=alice/r-42");
            assertThat(seen).containsExactly("permission:alice/r-42", "listener:alice/r-42");
            assertThat(afterCallback).containsExactly("null/null");
        }
    }

    @Test
    void callerSecurityContextAndMdcReachHooksOnSdkThreads() throws Exception {
        var seen = new CopyOnWriteArrayList<String>();
        beans.registerSingleton("auditHooks", new CopilotAgentHooks() {
            @Override
            public PreToolUseHookOutput onPreToolUse(CopilotAgentContext context, PreToolUseHookInput input) {
                seen.add(currentUser() + "/" + MDC.get("requestId"));
                return null;
            }
        });
        var session = session(null);
        var config = new AtomicReference<SessionConfig>();
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            config.set(invocation.getArgument(0));
            when(session.getSessionId()).thenReturn(config.get().getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> {
            var response = new CompletableFuture<AssistantMessageEvent>();
            Thread.ofPlatform().start(() -> {
                try {
                    config.get().getHooks().getOnPreToolUse()
                            .handle(new PreToolUseHookInput().setToolName("view"), null).join();
                    seen.add("after:" + currentUser() + "/" + MDC.get("requestId"));
                    response.complete(reply("ok"));
                } catch (Throwable ex) {
                    response.completeExceptionally(ex);
                }
            });
            return response;
        });
        try (var invoker = SpringTestSupport.invoker(client)) {
            var agent = agent(HookedAgent.class, invoker);
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
            MDC.put("requestId", "r-7");
            var result = agent.answer("one", "hi");
            SecurityContextHolder.clearContext();
            MDC.clear();
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
            assertThat(seen).containsExactly("alice/r-7", "after:null/null");
        }
    }

    @Test
    void toolsDoNotSeeStaleSdkThreadAuthenticationWhenTheCallerHasNone() throws Exception {
        beans.registerSingleton("securityTools", new SecurityAwareTools());
        beans.registerSingleton("permissions", (PermissionHandler) (request, invocation) ->
                CompletableFuture.completedFuture(PermissionRequestResult.reject("no")));
        beans.registerSingleton("listener", (CopilotAgentListener) (context, event) -> { });
        var session = session(null);
        var config = new AtomicReference<SessionConfig>();
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            config.set(invocation.getArgument(0));
            when(session.getSessionId()).thenReturn(config.get().getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> CompletableFuture.supplyAsync(() -> {
            var tool = config.get().getTools().getFirst();
            try {
                tool.handler().invoke(new ToolInvocation().setArguments(
                        new ObjectMapper().valueToTree(Map.of("prefix", "user=")))).join();
                return reply("unexpected");
            } catch (RuntimeException ex) {
                return reply("denied");
            }
        }, runnable -> Thread.ofPlatform().start(() -> {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("stale", "n/a"));
            runnable.run();
        })));
        try (var invoker = SpringTestSupport.invoker(client)) {
            var agent = agent(SecuredToolAgent.class, invoker);
            assertThat(agent.answer("one", "who am I?").get(5, TimeUnit.SECONDS)).isEqualTo("denied");
        }
    }

    private static String currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "null" : authentication.getName();
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private void assertCapacityReleasedOnce(TestAgent agent, CopilotSession session) throws Exception {
        var blocked = new CompletableFuture<AssistantMessageEvent>();
        var sent = new CountDownLatch(1);
        when(session.sendAndWait(any(), anyLong())).thenAnswer(invocation -> {
            sent.countDown();
            return blocked;
        });
        var second = agent.answer("two", "hello");
        assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> agent.answer("three", "hello").get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(RejectedExecutionException.class);
        blocked.complete(reply("second"));
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second");
    }

    private void awaitStopped(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (telemetry.stops.get() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(telemetry.stops.get()).isEqualTo(count);
    }

    private CopilotAgentInvoker invoker() {
        return SpringTestSupport.invoker(client, telemetry, new CopilotAgentExecutionLimits(1, 1));
    }

    private void newSessions(CopilotSession session) {
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
    }

    private CopilotAgentDefinition definition(Class<?> type) {
        return SpringTestSupport.definition(type, beans, environment, new DefaultResourceLoader(), properties);
    }

    private <T> T agent(Class<T> type, CopilotAgentInvoker invoker) {
        var factory = new CopilotAgentFactoryBean(type, invoker, beans, environment, new DefaultResourceLoader(), properties);
        factory.afterPropertiesSet();
        return type.cast(factory.getObject());
    }

    private static CopilotSession session(AssistantMessageEvent response) {
        var session = mock(CopilotSession.class);
        when(session.on(any())).thenReturn(mock(Closeable.class));
        if (response != null) {
            when(session.sendAndWait(any(), anyLong())).thenReturn(CompletableFuture.completedFuture(response));
        }
        return session;
    }

    private static AssistantMessageEvent reply(String content) {
        var event = new AssistantMessageEvent();
        var data = mock(AssistantMessageEvent.AssistantMessageEventData.class);
        when(data.content()).thenReturn(content);
        event.setData(data);
        return event;
    }

    @CopilotAgent(tokenResolverRef = "second")
    public interface SecondTokenAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent(customizerRefs = "rotating")
    public interface RotatingTokenAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent(hookRefs = "auditHooks")
    public interface HookedAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent(toolRefs = "securityTools", permissionHandlerRef = "permissions", listenerRefs = "listener")
    public interface SecuredToolAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }

    static final class RecordingTelemetry implements CopilotAgentTelemetry {
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicInteger stops = new AtomicInteger();

        @Override
        public Call start(String agent, String model) {
            return new Call() {
                @Override
                public void error(Throwable error) {
                    Throwable cause = error;
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    events.add("error:" + cause.getClass().getSimpleName());
                }

                @Override
                public void cancelled() {
                    events.add("cancelled");
                }

                @Override
                public void stop() {
                    events.add("stop");
                    stops.incrementAndGet();
                }
            };
        }
    }
}
