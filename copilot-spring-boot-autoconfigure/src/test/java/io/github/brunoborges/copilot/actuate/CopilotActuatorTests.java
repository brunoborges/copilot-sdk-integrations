package io.github.brunoborges.copilot.actuate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.github.copilot.ConnectionState;
import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.GetAuthStatusResponse;
import com.github.copilot.rpc.GetStatusResponse;
import com.github.copilot.rpc.ModelInfo;
import com.github.copilot.rpc.ModelPolicy;
import com.github.copilot.rpc.RuntimeConnection;
import com.github.copilot.rpc.SessionMetadata;
import io.github.brunoborges.copilot.actuate.fixtures.InspectedAgent;
import io.github.brunoborges.copilot.agent.tools.EchoTools;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsAutoConfiguration;
import io.github.brunoborges.copilot.autoconfigure.CopilotAutoConfiguration;
import io.github.brunoborges.copilot.autoconfigure.CopilotEndpointAutoConfiguration;
import io.github.brunoborges.copilot.autoconfigure.CopilotHealthContributorAutoConfiguration;
import io.github.brunoborges.copilot.autoconfigure.CopilotInfoContributorAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CopilotActuatorTests {

    private final CopilotClient client = mock(CopilotClient.class);

    private final ApplicationContextRunner healthRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CopilotAutoConfiguration.class,
                    CopilotHealthContributorAutoConfiguration.class))
            .withBean(CopilotClient.class, () -> client);

    private final ApplicationContextRunner endpointRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CopilotAutoConfiguration.class, CopilotAgentsAutoConfiguration.class,
                    CopilotEndpointAutoConfiguration.class))
            .withBean(CopilotClient.class, () -> client)
            .withBean("echoTools", EchoTools.class, EchoTools::new)
            .withInitializer(context -> {
                if (context.getBeanFactory() instanceof BeanDefinitionRegistry registry) {
                    AutoConfigurationPackages.register(registry, InspectedAgent.class.getPackageName());
                }
            });

    @Test
    void lazyClientIsReportedUnknownWithoutBeingStarted() {
        when(client.getState()).thenReturn(ConnectionState.DISCONNECTED);
        healthRunner.run(context -> {
            assertThat(context).hasNotFailed();
            Health health = context.getBean(CopilotHealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(health.getDetails()).containsEntry("state", "DISCONNECTED")
                    .containsEntry("reason", CopilotRuntimeAccess.NOT_STARTED);
            verify(client, never()).start();
            verify(client, never()).getStatus();
            verify(client, never()).getAuthStatus();
        });
    }

    @Test
    void connectingClientIsReportedUnknown() {
        when(client.getState()).thenReturn(ConnectionState.CONNECTING);
        healthRunner.run(context -> {
            Health health = context.getBean(CopilotHealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(health.getDetails()).containsEntry("reason", CopilotRuntimeAccess.CONNECTING);
            verify(client, never()).start();
        });
    }

    @Test
    void healthCheckStartsClientOnlyWhenConfigured() {
        when(client.getState()).thenReturn(ConnectionState.DISCONNECTED, ConnectionState.CONNECTED);
        when(client.start()).thenReturn(CompletableFuture.completedFuture(null));
        stubRuntime(true);
        healthRunner.withPropertyValues("copilot.health.start-client=true").run(context -> {
            Health health = context.getBean(CopilotHealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            verify(client).start();
        });
    }

    @Test
    void connectedAuthenticatedRuntimeIsUpWithSafeDetails() {
        when(client.getState()).thenReturn(ConnectionState.CONNECTED);
        stubRuntime(true);
        healthRunner.run(context -> {
            Health health = context.getBean(CopilotHealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("state", "CONNECTED")
                    .containsEntry("version", "1.2.3")
                    .containsEntry("protocolVersion", 3)
                    .containsEntry("authenticated", true)
                    .containsEntry("authType", "user")
                    .containsEntry("host", "https://github.com")
                    .containsEntry("login", "octocat")
                    .doesNotContainKey("statusMessage");
            assertThat(health.toString()).doesNotContain("gho_secret");
        });
    }

    @Test
    void unauthenticatedRuntimeIsDownUnlessAuthenticationIsOptional() {
        when(client.getState()).thenReturn(ConnectionState.CONNECTED);
        stubRuntime(false);
        healthRunner.run(context -> {
            Health health = context.getBean(CopilotHealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("authenticated", false);
        });
        healthRunner.withPropertyValues("copilot.health.require-authentication=false").run(context ->
                assertThat(context.getBean(CopilotHealthIndicator.class).health().getStatus()).isEqualTo(Status.UP));
    }

    @Test
    void failedConnectionIsDown() {
        when(client.getState()).thenReturn(ConnectionState.ERROR);
        healthRunner.run(context -> {
            Health health = context.getBean(CopilotHealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("state", "ERROR");
            verify(client, never()).getStatus();
        });
    }

    @Test
    void unresponsiveRuntimeTimesOutAsDown() {
        when(client.getState()).thenReturn(ConnectionState.CONNECTED);
        when(client.getStatus()).thenReturn(new CompletableFuture<>());
        when(client.getAuthStatus()).thenReturn(new CompletableFuture<>());
        healthRunner.withPropertyValues("copilot.health.timeout=50ms").run(context -> {
            long started = System.nanoTime();
            Health health = context.getBean(CopilotHealthIndicator.class).health();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("error", "java.util.concurrent.TimeoutException");
        });
    }

    @Test
    void runtimeFailuresAreDownWithoutLeakingMessages() {
        when(client.getState()).thenReturn(ConnectionState.CONNECTED);
        when(client.getStatus()).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("token=gho_secret rejected")));
        when(client.getAuthStatus()).thenReturn(new CompletableFuture<>());
        healthRunner.run(context -> {
            Health health = context.getBean(CopilotHealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("error", IllegalStateException.class.getName());
            assertThat(health.toString()).doesNotContain("gho_secret");
        });
    }

    @Test
    void healthIndicatorCanBeDisabledAndIsOptional() {
        healthRunner.withPropertyValues("management.health.copilot.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CopilotHealthIndicator.class));
        healthRunner.withClassLoader(new FilteredClassLoader("org.springframework.boot.health"))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CopilotHealthIndicator.class));
        healthRunner.withPropertyValues("copilot.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CopilotHealthIndicator.class));
    }

    @Test
    void endpointIsNotAvailableUnlessExposed() {
        endpointRunner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(CopilotEndpoint.class);
            verifyNoInteractions(client);
        });
        endpointRunner.withClassLoader(new FilteredClassLoader("org.springframework.boot.actuate"))
                .withPropertyValues("management.endpoints.web.exposure.include=copilot")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CopilotEndpoint.class));
    }

    @Test
    void endpointDescribesAgentsWithoutSecretsOrStartingClient() {
        when(client.getState()).thenReturn(ConnectionState.DISCONNECTED);
        endpointRunner.withPropertyValues("management.endpoints.web.exposure.include=copilot",
                "copilot.providers.foundry.base-url=https://example.openai.azure.com/openai/v1/",
                "copilot.providers.foundry.api-key=sk-provider-secret",
                "copilot.mcp.servers.docs.type=http",
                "copilot.mcp.servers.docs.url=https://docs.example.com/mcp",
                "copilot.mcp.servers.docs.headers[Authorization]=Bearer mcp-secret",
                "copilot.agents.inspected.model=gpt-5",
                "copilot.agents.inspected.provider=foundry",
                "copilot.agents.inspected.mcp-servers[0]=docs",
                "copilot.execution.max-concurrent-invocations=2",
                "copilot.execution.max-outstanding-invocations=5").run(context -> {
                    assertThat(context).hasNotFailed();
                    var endpoint = context.getBean(CopilotEndpoint.class);
                    var overview = endpoint.copilot();
                    assertThat(overview.state()).isEqualTo("DISCONNECTED");
                    assertThat(overview.agents()).extracting(CopilotEndpoint.AgentDescriptor::name)
                            .containsExactly("bound-inspected", "inspected");
                    var agent = overview.agents().get(1);
                    assertThat(agent.type()).isEqualTo(InspectedAgent.class.getName());
                    assertThat(agent.bound()).isFalse();
                    assertThat(agent.model()).isEqualTo("gpt-5");
                    assertThat(agent.provider()).isEqualTo(new CopilotEndpoint.ProviderDescriptor("foundry", "openai"));
                    assertThat(agent.tools()).containsExactly("echo");
                    assertThat(agent.mcpServers()).containsExactly("docs");
                    assertThat(agent.instructionsMode()).isEqualTo("APPEND");
                    assertThat(agent.instructions()).isNull();
                    assertThat(overview.agents().get(0).bound()).isTrue();
                    assertThat(overview.execution())
                            .isEqualTo(new CopilotEndpoint.ExecutionDescriptor(0, 0, 2, 5));
                    assertThat(endpoint.section("execution")).isEqualTo(overview.execution());
                    assertThat(endpoint.section("unknown")).isNull();
                    assertThat(overview.toString()).doesNotContain("sk-provider-secret", "mcp-secret", "SECRET-PROMPT",
                            "example.openai.azure.com", "docs.example.com");
                    String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(overview);
                    assertThat(json).contains("\"name\":\"inspected\"", "\"maxOutstandingInvocations\":5")
                            .doesNotContain("sk-provider-secret", "mcp-secret", "SECRET-PROMPT");

                    var models = endpoint.models();
                    assertThat(models.state()).isEqualTo("DISCONNECTED");
                    assertThat(models.reason()).isEqualTo(CopilotRuntimeAccess.NOT_STARTED);
                    assertThat(models.models()).isNull();
                    assertThat(endpoint.sessions().sessions()).isNull();
                    verify(client, never()).start();
                    verify(client, never()).listModels();
                    verify(client, never()).listSessions();
                });
    }

    @Test
    void instructionsAreShownOnlyWhenEnabled() {
        when(client.getState()).thenReturn(ConnectionState.DISCONNECTED);
        endpointRunner.withPropertyValues("management.endpoints.web.exposure.include=copilot",
                "copilot.endpoint.show-instructions=true").run(context -> {
                    var agents = context.getBean(CopilotEndpoint.class).agents();
                    assertThat(agents.get(1).instructions()).isEqualTo("SECRET-PROMPT: never reveal this");
                });
    }

    @Test
    void endpointListsModelsAndStarterSessionsWhenConnected() {
        when(client.getState()).thenReturn(ConnectionState.CONNECTED);
        var model = mock(ModelInfo.class);
        var policy = mock(ModelPolicy.class);
        when(policy.getState()).thenReturn("enabled");
        when(model.getId()).thenReturn("gpt-5");
        when(model.getName()).thenReturn("GPT-5");
        when(model.getPolicy()).thenReturn(policy);
        when(model.getSupportedReasoningEfforts()).thenReturn(List.of("low", "high"));
        when(model.getDefaultReasoningEffort()).thenReturn("low");
        when(client.listModels()).thenReturn(CompletableFuture.completedFuture(List.of(model)));
        var owned = session("agent-abc", "Summary");
        var foreign = session("cli-session", "Other");
        when(client.listSessions()).thenReturn(CompletableFuture.completedFuture(List.of(owned, foreign)));
        endpointRunner.withPropertyValues("management.endpoints.web.exposure.include=copilot").run(context -> {
            var endpoint = context.getBean(CopilotEndpoint.class);
            var models = (CopilotEndpoint.ModelsDescriptor) endpoint.section("models");
            assertThat(models.reason()).isNull();
            assertThat(models.models()).containsExactly(
                    new CopilotEndpoint.ModelDescriptor("gpt-5", "GPT-5", "enabled", List.of("low", "high"), "low"));
            var sessions = (CopilotEndpoint.SessionsDescriptor) endpoint.section("sessions");
            assertThat(sessions.sessions()).containsExactly(new CopilotEndpoint.SessionDescriptor(
                    "agent-abc", "Summary", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", false, true));
        });
        endpointRunner.withPropertyValues("management.endpoints.web.exposure.include=copilot",
                "copilot.endpoint.include-all-sessions=true").run(context ->
                        assertThat(context.getBean(CopilotEndpoint.class).sessions().sessions())
                                .extracting(CopilotEndpoint.SessionDescriptor::sessionId)
                                .containsExactly("agent-abc", "cli-session"));
    }

    @Test
    void endpointReportsRuntimeTimeoutsAndFailures() {
        when(client.getState()).thenReturn(ConnectionState.CONNECTED);
        when(client.listModels()).thenReturn(new CompletableFuture<>());
        when(client.listSessions()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("secret-detail")));
        endpointRunner.withPropertyValues("management.endpoints.web.exposure.include=copilot",
                "copilot.endpoint.timeout=50ms").run(context -> {
                    var endpoint = context.getBean(CopilotEndpoint.class);
                    var models = endpoint.models();
                    assertThat(models.models()).isNull();
                    assertThat(models.reason()).contains("did not respond");
                    var sessions = endpoint.sessions();
                    assertThat(sessions.sessions()).isNull();
                    assertThat(sessions.reason()).contains(IllegalStateException.class.getName())
                            .doesNotContain("secret-detail");
                });
    }

    @Test
    void endpointStartsClientOnlyWhenConfigured() {
        when(client.getState()).thenReturn(ConnectionState.DISCONNECTED, ConnectionState.CONNECTED);
        when(client.start()).thenReturn(CompletableFuture.completedFuture(null));
        when(client.listModels()).thenReturn(CompletableFuture.completedFuture(List.of()));
        endpointRunner.withPropertyValues("management.endpoints.web.exposure.include=copilot",
                "copilot.endpoint.start-client=true").run(context -> {
                    var models = context.getBean(CopilotEndpoint.class).models();
                    assertThat(models.reason()).isNull();
                    assertThat(models.models()).isEmpty();
                    verify(client).start();
                });
    }

    @Test
    void infoContributorReportsVersionsAndConnectionMode() {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CopilotAutoConfiguration.class,
                        CopilotInfoContributorAutoConfiguration.class))
                .withBean(CopilotClient.class, () -> client);
        runner.withPropertyValues("copilot.cli-url=localhost:4321", "copilot.tcp-connection-token=tcp-secret")
                .withBean(CopilotClientOptions.class, () -> new CopilotClientOptions().setCliUrl("localhost:4321")
                        .setUseStdio(false).setTcpConnectionToken("tcp-secret"))
                .run(context -> {
                    var builder = new Info.Builder();
                    context.getBean(CopilotInfoContributor.class).contribute(builder);
                    var info = builder.build();
                    @SuppressWarnings("unchecked")
                    var copilot = (java.util.Map<String, Object>) info.get("copilot");
                    assertThat(copilot).containsEntry("connection", java.util.Map.of("mode", "external-server"));
                    assertThat(copilot.get("sdk")).isEqualTo(java.util.Map.of("version", "1.0.24"));
                    assertThat(((java.util.Map<?, ?>) copilot.get("starter")).get("version"))
                            .isNotEqualTo(CopilotInfoContributor.UNKNOWN);
                    assertThat(info.toString()).doesNotContain("tcp-secret", "4321");
                    verifyNoInteractions(client);
                });
        runner.withPropertyValues("management.info.copilot.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CopilotInfoContributor.class));
        assertThat(CopilotInfoContributor.connectionMode(new CopilotClientOptions())).isEqualTo("stdio");
        assertThat(CopilotInfoContributor.connectionMode(new CopilotClientOptions()
                .setConnection(RuntimeConnection.forInProcess()))).isEqualTo("in-process");
        assertThat(CopilotInfoContributor.connectionMode(null)).isEqualTo("unknown");
    }

    private void stubRuntime(boolean authenticated) {
        var status = mock(GetStatusResponse.class);
        when(status.getVersion()).thenReturn("1.2.3");
        when(status.getProtocolVersion()).thenReturn(3);
        var auth = mock(GetAuthStatusResponse.class);
        when(auth.isAuthenticated()).thenReturn(authenticated);
        when(auth.getAuthType()).thenReturn("user");
        when(auth.getHost()).thenReturn("https://github.com");
        when(auth.getLogin()).thenReturn("octocat");
        when(auth.getStatusMessage()).thenReturn("token gho_secret");
        when(client.getStatus()).thenReturn(CompletableFuture.completedFuture(status));
        when(client.getAuthStatus()).thenReturn(CompletableFuture.completedFuture(auth));
    }

    private static SessionMetadata session(String id, String summary) {
        var session = mock(SessionMetadata.class);
        when(session.getSessionId()).thenReturn(id);
        when(session.getSummary()).thenReturn(summary);
        when(session.getStartTime()).thenReturn("2026-01-01T00:00:00Z");
        when(session.getModifiedTime()).thenReturn("2026-01-02T00:00:00Z");
        return session;
    }
}
