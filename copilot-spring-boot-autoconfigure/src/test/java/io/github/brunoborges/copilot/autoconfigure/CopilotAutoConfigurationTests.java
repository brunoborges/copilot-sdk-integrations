package io.github.brunoborges.copilot.autoconfigure;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.ConnectionState;
import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CopilotAutoConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CopilotAutoConfiguration.class));

    @Test
    void createsLazyClientWithSdkDefaults() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CopilotClient.class).hasSingleBean(CopilotClientOptions.class);
            assertThat(context.getBean(CopilotClient.class).getState()).isEqualTo(ConnectionState.DISCONNECTED);
            var options = context.getBean(CopilotClientOptions.class);
            assertThat(options.isAutoStart()).isTrue();
            assertThat(options.isUseStdio()).isTrue();
            assertThat(options.getCliPath()).isNull();
            assertThat(options.getCliUrl()).isNull();
            assertThat(options.getLogLevel()).isEqualTo("info");
            assertThat(options.getGitHubToken()).isNull();
            assertThat(options.getUseLoggedInUser()).isEmpty();
            var properties = context.getBean(CopilotProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.eagerStart()).isFalse();
            assertThat(properties.startupTimeout()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void mapsLocalRuntimePropertiesWithoutLaunchingRuntime() {
        runner.withPropertyValues("copilot.cli-path=/missing/copilot",
                "copilot.cli-args[0]=--custom-flag", "copilot.cli-args[1]=value",
                "copilot.working-directory=/work", "copilot.log-level=debug",
                "copilot.startup-timeout=5s").run(context -> {
                    assertThat(context).hasNotFailed();
                    var options = context.getBean(CopilotClientOptions.class);
                    assertThat(options.getCliPath()).isEqualTo("/missing/copilot");
                    assertThat(options.getCliArgs()).containsExactly("--custom-flag", "value");
                    assertThat(options.getCwd()).isEqualTo("/work");
                    assertThat(options.getLogLevel()).isEqualTo("debug");
                    assertThat(context.getBean(CopilotClient.class).getState()).isEqualTo(ConnectionState.DISCONNECTED);
                    assertThat(context.getBean(CopilotProperties.class).startupTimeout()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"4321", "localhost:4321", "http://localhost:4321", "https://localhost:4321", "[::1]:4321"})
    void mapsExternalServerPropertiesWithoutConnecting(String address) {
        runner.withPropertyValues("copilot.cli-url=" + address, "copilot.tcp-connection-token=test-token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var options = context.getBean(CopilotClientOptions.class);
                    assertThat(options.getCliUrl()).isEqualTo(address);
                    assertThat(options.isUseStdio()).isFalse();
                    assertThat(options.getTcpConnectionToken()).isEqualTo("test-token");
                    assertThat(context.getBean(CopilotProperties.class).toString()).doesNotContain("test-token");
                    assertThat(context.getBean(CopilotClient.class).getState()).isEqualTo(ConnectionState.DISCONNECTED);
                });
    }

    @Test
    void disabledIntegrationCreatesNoBeansEvenWithInvalidProperties() {
        runner.withPropertyValues("copilot.enabled=false", "copilot.startup-timeout=invalid").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(CopilotClient.class)
                        .doesNotHaveBean(CopilotClientOptions.class).doesNotHaveBean(CopilotProperties.class));
    }

    @Test
    void backsOffWhenSdkIsAbsent() {
        runner.withClassLoader(new FilteredClassLoader("com.github.copilot")).run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(CopilotAutoConfiguration.class)
                        .doesNotHaveBean(CopilotProperties.class));
    }

    @Test
    void userClientIsNotStartedReconfiguredOrClosedByStarter() {
        var client = mock(CopilotClient.class);
        runner.withBean(CopilotClient.class, () -> client, definition -> definition.setDestroyMethodName(""))
                .withPropertyValues("copilot.eager-start=true", "copilot.startup-timeout=invalid")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(CopilotClient.class)
                            .doesNotHaveBean(CopilotClientOptions.class).doesNotHaveBean(CopilotProperties.class);
                    assertThat(context.getBean(CopilotClient.class)).isSameAs(client);
                });
        verifyNoInteractions(client);
    }

    @Test
    void userOptionsReplacePropertyMapping() {
        var options = new CopilotClientOptions().setCliPath("/custom/copilot").setAutoStart(false);
        runner.withBean(CopilotClientOptions.class, () -> options)
                .withPropertyValues("copilot.cli-path=/ignored/copilot").run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(CopilotClientOptions.class);
                    assertThat(context.getBean(CopilotClientOptions.class)).isSameAs(options);
                    assertThat(options.getCliPath()).isEqualTo("/custom/copilot");
                    assertThat(options.isAutoStart()).isFalse();
                });
    }

    @Test
    void closesOwnedLazyClientExactlyOnceWithoutStartingIt() {
        try (var construction = mockConstruction(CopilotClient.class)) {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                verify(context.getBean(CopilotClient.class), never()).start();
                verify(context.getBean(CopilotClient.class), never()).close();
            });
            assertThat(construction.constructed()).hasSize(1);
            verify(construction.constructed().getFirst()).close();
            verify(construction.constructed().getFirst(), never()).start();
        }
    }

    @Test
    void eagerlyStartsAndClosesOwnedClient() {
        try (var construction = mockConstruction(CopilotClient.class,
                (client, context) -> when(client.start()).thenReturn(CompletableFuture.completedFuture(null)))) {
            runner.withPropertyValues("copilot.eager-start=true").run(context -> {
                assertThat(context).hasNotFailed();
                verify(context.getBean(CopilotClient.class)).start();
                verify(context.getBean(CopilotClient.class), never()).close();
            });
            verify(construction.constructed().getFirst()).close();
        }
    }

    @Test
    void eagerStartupIsNotDeferredByGlobalLazyInitialization() {
        try (var construction = mockConstruction(CopilotClient.class,
                (client, context) -> when(client.start()).thenReturn(CompletableFuture.completedFuture(null)))) {
            runner.withInitializer(context -> context.addBeanFactoryPostProcessor(
                    new LazyInitializationBeanFactoryPostProcessor()))
                    .withPropertyValues("copilot.eager-start=true").run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(construction.constructed()).hasSize(1);
                        verify(construction.constructed().getFirst()).start();
                    });
            verify(construction.constructed().getFirst()).close();
        }
    }

    @Test
    void eagerFailureFailsContextAndClosesPartiallyInitializedClientOnce() {
        var error = new IllegalStateException("runtime unavailable");
        try (var construction = mockConstruction(CopilotClient.class,
                (client, context) -> when(client.start()).thenReturn(CompletableFuture.failedFuture(error)))) {
            runner.withPropertyValues("copilot.eager-start=true").run(context ->
                    assertThat(context).hasFailed().getFailure().hasRootCause(error));
            verify(construction.constructed().getFirst()).close();
        }
    }

    @Test
    void realSdkStartupFailureFailsContextWithoutLaunchingRuntime() {
        var options = new CopilotClientOptions().setExecutor(task -> {
            throw new RejectedExecutionException("test executor rejects startup");
        });
        runner.withBean(CopilotClientOptions.class, () -> options)
                .withPropertyValues("copilot.eager-start=true").run(context ->
                        assertThat(context).hasFailed().getFailure()
                                .hasRootCauseInstanceOf(RejectedExecutionException.class));
    }

    @ParameterizedTest
    @MethodSource("invalidConfigurations")
    void rejectsInvalidConfiguration(String[] properties, String message) {
        runner.withPropertyValues(properties).run(context ->
                assertThat(context).hasFailed().getFailure().hasStackTraceContaining(message));
    }

    static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
                Arguments.of(new String[]{"copilot.startup-timeout=0s"}, "copilot.startup-timeout"),
                Arguments.of(new String[]{"copilot.startup-timeout=-1s"}, "copilot.startup-timeout"),
                Arguments.of(new String[]{"copilot.startup-timeout=1000000d"}, "copilot.startup-timeout"),
                Arguments.of(new String[]{"copilot.startup-timeout=invalid"}, "startup-timeout"),
                Arguments.of(new String[]{"copilot.cli-path="}, "copilot.cli-path must not be blank"),
                Arguments.of(new String[]{"copilot.cli-args[0]="}, "copilot.cli-args"),
                Arguments.of(new String[]{"copilot.working-directory="}, "copilot.working-directory"),
                Arguments.of(new String[]{"copilot.log-level="}, "copilot.log-level"),
                Arguments.of(new String[]{"copilot.cli-url="}, "copilot.cli-url"),
                Arguments.of(new String[]{"copilot.cli-url=localhost"}, "copilot.cli-url"),
                Arguments.of(new String[]{"copilot.cli-url=0"}, "copilot.cli-url"),
                Arguments.of(new String[]{"copilot.cli-url=localhost:65536"}, "copilot.cli-url"),
                Arguments.of(new String[]{"copilot.cli-url=tcp://localhost:4321"}, "copilot.cli-url"),
                Arguments.of(new String[]{"copilot.cli-url=http://localhost:4321/path"}, "copilot.cli-url"),
                Arguments.of(new String[]{"copilot.cli-url=http://localhost:4321?query"}, "copilot.cli-url"),
                Arguments.of(new String[]{"copilot.cli-url=http://localhost:4321#fragment"}, "copilot.cli-url"),
                Arguments.of(new String[]{"copilot.cli-url=localhost:4321", "copilot.cli-path=/bin/copilot"},
                        "cannot be combined"),
                Arguments.of(new String[]{"copilot.cli-url=localhost:4321", "copilot.cli-args[0]=--flag"},
                        "cannot be combined"),
                Arguments.of(new String[]{"copilot.cli-url=localhost:4321", "copilot.working-directory=/work"},
                        "cannot be combined"),
                Arguments.of(new String[]{"copilot.tcp-connection-token=test-token"}, "requires copilot.cli-url"),
                Arguments.of(new String[]{"copilot.cli-url=localhost:4321", "copilot.tcp-connection-token="},
                        "copilot.tcp-connection-token must not be blank"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://user:private-value@localhost:4321", "http://[private-value"})
    void malformedServerAddressDoesNotLeakCredentials(String address) {
        runner.withPropertyValues("copilot.cli-url=" + address).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("copilot.cli-url")
                    .hasStackTraceContaining("credentials, paths, queries, and fragments are not supported");
            var stackTrace = new StringWriter();
            context.getStartupFailure().printStackTrace(new PrintWriter(stackTrace));
            assertThat(stackTrace.toString()).doesNotContain("private-value");
        });
    }

    @Test
    void bootDiscoversAutoConfigurationThroughImportsResource() {
        new ApplicationContextRunner().withUserConfiguration(DiscoveryConfiguration.class).run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(CopilotClient.class));
    }

    @Test
    void generatesConfigurationMetadata() throws Exception {
        try (var input = new ClassPathResource("META-INF/spring-configuration-metadata.json").getInputStream()) {
            var properties = new ObjectMapper().readTree(input).get("properties");
            var names = new ArrayList<String>();
            properties.forEach(property -> names.add(property.get("name").asText()));
            assertThat(names).containsExactlyInAnyOrder("copilot.enabled", "copilot.eager-start",
                    "copilot.startup-timeout", "copilot.cli-path", "copilot.cli-args",
                    "copilot.working-directory", "copilot.log-level", "copilot.cli-url",
                    "copilot.tcp-connection-token", "copilot.agents", "copilot.mcp.servers", "copilot.providers",
                    "copilot.observations.enabled",
                    "copilot.execution.max-concurrent-invocations", "copilot.execution.max-outstanding-invocations",
                    "copilot.health.timeout", "copilot.health.start-client",
                    "copilot.health.require-authentication", "copilot.endpoint.timeout",
                    "copilot.endpoint.start-client", "copilot.endpoint.show-instructions",
                    "copilot.endpoint.include-all-sessions", "management.endpoint.copilot.access",
                    "management.endpoint.copilot.cache.time-to-live",
                    "copilot.security.oauth2.registration-id", "copilot.security.oauth2.ambient-fallback");
            for (var property : properties) {
                assertThat(property.get("description").asText()).isNotBlank();
                if (property.get("name").asText().equals("copilot.startup-timeout")) {
                    assertThat(property.get("defaultValue").asText()).isEqualTo("30s");
                }
            }
        }
    }

    @Test
    void connectionDetailsTakePrecedenceOverServerAndLocalRuntimeProperties() {
        CopilotConnectionDetails details = new CopilotConnectionDetails() {
            @Override
            public String cliUrl() {
                return "localhost:5432";
            }

            @Override
            public String tcpConnectionToken() {
                return "container-token";
            }
        };
        runner.withBean(CopilotConnectionDetails.class, () -> details)
                .withPropertyValues("copilot.cli-path=/missing/copilot", "copilot.cli-args[0]=--flag",
                        "copilot.working-directory=/work")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var options = context.getBean(CopilotClientOptions.class);
                    assertThat(options.getCliUrl()).isEqualTo("localhost:5432");
                    assertThat(options.getTcpConnectionToken()).isEqualTo("container-token");
                    assertThat(options.isUseStdio()).isFalse();
                    assertThat(options.getCliPath()).isNull();
                    assertThat(context.getBean(CopilotClient.class).getState()).isEqualTo(ConnectionState.DISCONNECTED);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class DiscoveryConfiguration {
    }
}
