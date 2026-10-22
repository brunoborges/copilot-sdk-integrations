package io.github.brunoborges.copilot.agent;

import java.time.Duration;
import java.util.List;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.McpHttpServerConfig;
import com.github.copilot.rpc.McpStdioServerConfig;
import io.github.brunoborges.copilot.agent.fixtures.TestAgent;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsAutoConfiguration;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsProperties;
import io.github.brunoborges.copilot.autoconfigure.CopilotAutoConfiguration;
import io.github.brunoborges.copilot.autoconfigure.CopilotMcpProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class McpConfigurationTests {

    private final CopilotClient client = mock(CopilotClient.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CopilotAutoConfiguration.class, CopilotAgentsAutoConfiguration.class))
            .withBean(CopilotClient.class, () -> client)
            .withInitializer(context -> {
                if (context.getBeanFactory() instanceof BeanDefinitionRegistry registry) {
                    AutoConfigurationPackages.register(registry, TestAgent.class.getPackageName());
                } else {
                    throw new IllegalStateException("Test context requires a bean definition registry");
                }
            });

    @Test
    void bindsBothTransportsAndPerAgentSelectionWithoutConnecting() {
        runner.withPropertyValues(
                "copilot.mcp.servers.files.command=/usr/bin/node",
                "copilot.mcp.servers.files.args[0]=/opt/mcp/server.js",
                "copilot.mcp.servers.files.args[1]=",
                "copilot.mcp.servers.files.env[SERVER_MODE]=readonly",
                "copilot.mcp.servers.files.working-directory=/workspace",
                "copilot.mcp.servers.files.tools[0]=read_file",
                "copilot.mcp.servers.files.timeout=1250ms",
                "copilot.mcp.servers.docs.type=http",
                "copilot.mcp.servers.docs.url=https://docs.example.com/mcp",
                "mcp-test.token=example-token",
                "copilot.mcp.servers.docs.headers[Authorization]=Bearer ${mcp-test.token}",
                "copilot.mcp.servers.docs.tools[0]=*",
                "copilot.agents.test-agent.mcp-servers[0]=files",
                "copilot.agents.test-agent.mcp-servers[1]=docs").run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(CopilotMcpProperties.class);
                    var configs = properties.toServerConfigs();
                    var local = (McpStdioServerConfig) configs.get("files");
                    assertThat(local.getCommand()).isEqualTo("/usr/bin/node");
                    assertThat(local.getArgs()).containsExactly("/opt/mcp/server.js", "");
                    assertThat(local.getEnv()).containsEntry("SERVER_MODE", "readonly");
                    assertThat(local.getWorkingDirectory()).isEqualTo("/workspace");
                    assertThat(local.getTools()).containsExactly("read_file");
                    assertThat(local.getTimeout()).isEqualTo(1250);
                    var remote = (McpHttpServerConfig) configs.get("docs");
                    assertThat(remote.getUrl()).isEqualTo("https://docs.example.com/mcp");
                    assertThat(remote.getHeaders()).containsEntry("Authorization", "Bearer example-token");
                    assertThat(remote.getTools()).containsExactly("*");
                    assertThat(remote.getTimeout()).isNull();
                    assertThat(properties.servers().get("docs").toString()).doesNotContain("example-token");
                    assertThat(properties.toServerConfigs().get("files")).isNotSameAs(local);
                    assertThat(context.getBean(CopilotAgentsProperties.class).agents().get("test-agent").mcpServers())
                            .containsExactly("files", "docs");
                    verifyNoInteractions(client);
                });
    }

    @Test
    void defaultsToNoSelectedServersAndNoExposedTools() {
        runner.withPropertyValues("copilot.mcp.servers.files.command=node").run(context -> {
            assertThat(context).hasNotFailed();
            var server = context.getBean(CopilotMcpProperties.class).toServerConfigs().get("files");
            assertThat(server).isInstanceOf(McpStdioServerConfig.class);
            assertThat(server.getTools()).isEmpty();
            assertThat(server.getTimeout()).isNull();
            verifyNoInteractions(client);
        });
    }

    @Test
    void unknownMcpServerNamesBothLookupLocations() {
        runner.withPropertyValues("copilot.agents.test-agent.mcp-servers[0]=missing").run(context ->
                assertThat(context).hasFailed().getFailure().hasStackTraceContaining(
                        "define copilot.mcp.servers.missing or an SDK McpServerConfig bean named 'missing'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "copilot.mcp.servers.files.command=",
            "copilot.mcp.servers.files.type=unknown",
            "copilot.mcp.servers.files.url=https://example.com/mcp",
            "copilot.mcp.servers.files.headers[Authorization]=example",
            "copilot.mcp.servers.files.working-directory= ",
            "copilot.mcp.servers.files.tools[0]= ",
            "copilot.mcp.servers.files.timeout=0ms",
            "copilot.mcp.servers.files.timeout=-1ms",
            "copilot.mcp.servers.files.timeout=1ns",
            "copilot.mcp.servers.files.timeout=2147483648ms",
            "copilot.agents.test-agent.mcp-servers[0]=missing",
            "copilot.agents.test-agent.mcp-servers[0]= "
    })
    void rejectsInvalidConfiguration(String invalid) {
        runner.withPropertyValues("copilot.mcp.servers.files.command=node", invalid).run(context -> {
            assertThat(context).hasFailed();
            verify(client, never()).start();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/relative", "ftp://example.com/mcp", "https://user:pass@example.com/mcp",
            "https://example.com/mcp#fragment", "http://localhost:0/mcp", "http://localhost:65536/mcp"})
    void rejectsInvalidHttpUrls(String url) {
        runner.withPropertyValues("copilot.mcp.servers.remote.type=http",
                "copilot.mcp.servers.remote.url=" + url).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void malformedHttpUrlFailureDoesNotEchoSecrets() {
        runner.withPropertyValues("copilot.mcp.servers.remote.type=http",
                "copilot.mcp.servers.remote.url=https://example.com/mcp?token=secret-value with space")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("MCP url must be an absolute HTTP(S) address")
                        .satisfies(failure -> assertThat(stackTrace(failure)).doesNotContain("secret-value")));
    }

    private static String stackTrace(Throwable failure) {
        var writer = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"command=node", "args[0]=arg", "env[MODE]=test", "working-directory=/tmp"})
    void rejectsLocalFieldsOnHttpServers(String localField) {
        runner.withPropertyValues("copilot.mcp.servers.remote.type=http",
                "copilot.mcp.servers.remote.url=https://example.com/mcp",
                "copilot.mcp.servers.remote." + localField).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsAmbiguousAndDuplicateReferencesAtInitialization() {
        runner.withPropertyValues("copilot.mcp.servers.files.command=node",
                "copilot.agents.test-agent.mcp-servers[0]=files")
                .withBean("files", McpStdioServerConfig.class, () -> new McpStdioServerConfig().setCommand("other"))
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("Ambiguous MCP server definition"));
        runner.withPropertyValues("copilot.mcp.servers.files.command=node",
                "copilot.agents.test-agent.mcp-servers[0]=files", "copilot.agents.test-agent.mcp-servers[1]=files")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("Duplicate MCP server reference"));
    }

    @Test
    void emptyPropertySelectionIsDistinctFromAnAbsentOverride() {
        runner.withPropertyValues("copilot.agents.test-agent.mcp-servers=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CopilotAgentsProperties.class).agents().get("test-agent").mcpServers()).isEmpty();
        });
        assertThat(new CopilotAgentsProperties.Agent(null, null, Duration.ofSeconds(60)).mcpServers()).isNull();
        assertThat(new CopilotAgentsProperties.Agent(null, null, Duration.ofSeconds(60), List.of()).mcpServers()).isEmpty();
    }
}
