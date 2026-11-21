package com.microsoft.copilot.integrations.springboot.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.microsoft.copilot.ConnectionState;
import com.microsoft.copilot.CopilotClient;
import com.microsoft.copilot.rpc.CopilotClientOptions;
import com.microsoft.copilot.rpc.TcpRuntimeConnection;

import com.microsoft.copilot.integrations.springboot.test.app.AdvisorApplication;
import com.microsoft.copilot.integrations.springboot.test.testcontainers.CopilotContainer;

/**
 * Runs against a real headless Copilot CLI. Requires Docker, a Copilot CLI image ({@code COPILOT_TEST_IMAGE}) and a
 * GitHub token ({@code COPILOT_GITHUB_TOKEN}); skipped otherwise.
 */
@EnabledIfEnvironmentVariable(named = "COPILOT_TEST_IMAGE", matches = ".+")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = AdvisorApplication.class)
class CopilotContainerTests {

    @Container
    @ServiceConnection
    static CopilotContainer copilot = new CopilotContainer(System.getenv().getOrDefault("COPILOT_TEST_IMAGE", "unset"))
            .withGitHubToken(System.getenv("COPILOT_GITHUB_TOKEN"));

    @Autowired
    CopilotClient client;

    @Autowired
    CopilotClientOptions options;

    @Test
    void connectsToTheContainer() {
        assertThat(options.getRuntime()).isInstanceOfSatisfying(TcpRuntimeConnection.class, tcp -> {
            assertThat(tcp.getHost()).isEqualTo(copilot.getHost());
            assertThat(tcp.getPort()).isEqualTo(copilot.getMappedPort(copilot.getCopilotPort()));
            assertThat(tcp.getConnectionToken()).isEqualTo(copilot.getTcpConnectionToken());
        });
        client.start().join();
        assertThat(client.getState()).isEqualTo(ConnectionState.CONNECTED);
    }
}
