package com.microsoft.copilot.integrations.core.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.microsoft.copilot.AllowCopilotExperimental;
import com.microsoft.copilot.rpc.CopilotClientMode;
import com.microsoft.copilot.rpc.InProcessRuntimeConnection;
import com.microsoft.copilot.rpc.SpawnedStdioRuntimeConnection;
import com.microsoft.copilot.rpc.TcpRuntimeConnection;

import com.microsoft.copilot.integrations.core.internal.CopilotRuntimeConnections.Settings;
import com.microsoft.copilot.integrations.core.internal.CopilotRuntimeConnections.Transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@AllowCopilotExperimental
class CopilotRuntimeConnectionsTests {

    private static final Set<String> JNA = Set.of(CopilotRuntimeConnections.JNA_CLASS);

    @Test
    void inProcessReportsEveryRejectedSetting() {
        var settings = new Settings("/bin/copilot", List.of("--x"), "/work", null, null, null, Transport.INPROCESS);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CopilotRuntimeConnections.clientOptions("copilot.", settings))
                .withMessageStartingWith("copilot.connection=inprocess cannot be combined with copilot.cli-path, "
                        + "copilot.cli-args, copilot.working-directory:");
    }

    @Test
    void inProcessRejectsServerSettings() {
        var settings = new Settings(null, null, null, null, "localhost:3000", "t", Transport.INPROCESS);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CopilotRuntimeConnections.clientOptions("quarkus.copilot.", settings))
                .withMessageContaining("quarkus.copilot.cli-url, quarkus.copilot.tcp-connection-token");
    }

    @Test
    void stdioPinsTheConfiguredExecutableArgumentsAndWorkingDirectory() {
        var settings = new Settings("/bin/copilot", List.of("--x"), "/work", "debug", null, null, Transport.STDIO);

        var options = CopilotRuntimeConnections.clientOptions("copilot.", settings, "inprocess");

        assertThat(options.getMode()).isEqualTo(CopilotClientMode.CODING_AGENT);
        assertThat(options.getObservability().getLogLevel()).isEqualTo("debug");
        assertThat(options.getRuntime()).isInstanceOfSatisfying(SpawnedStdioRuntimeConnection.class, stdio -> {
            assertThat(stdio.getPath()).isEqualTo("/bin/copilot");
            assertThat(stdio.getArgs()).containsExactly("--x");
            assertThat(stdio.getWorkingDirectory()).isEqualTo("/work");
        });
    }

    @Test
    void stdioRejectsAnExistingServer() {
        var settings = new Settings(null, null, null, null, "localhost:3000", null, Transport.STDIO);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CopilotRuntimeConnections.clientOptions("copilot.", settings))
                .withMessageStartingWith("copilot.connection=stdio cannot be combined with copilot.cli-url");
    }

    @Test
    void omittedConnectionKeepsStdioUnlessTheEnvironmentSelectsInProcess() {
        var settings = new Settings(null, null, null, null, null, null, null);

        assertThat(CopilotRuntimeConnections.clientOptions("copilot.", settings, null).getRuntime())
                .isInstanceOf(SpawnedStdioRuntimeConnection.class);
        assertThat(CopilotRuntimeConnections.clientOptions("copilot.", settings, "STDIO").getRuntime())
                .isInstanceOf(SpawnedStdioRuntimeConnection.class);
        assertThat(CopilotRuntimeConnections.clientOptions("copilot.", settings, "inprocess").getRuntime())
                .isInstanceOf(InProcessRuntimeConnection.class);
        assertThat(CopilotRuntimeConnections.clientOptions("copilot.",
                new Settings("/bin/copilot", null, null, null, null, null, null), "inprocess").getRuntime())
                .isInstanceOf(SpawnedStdioRuntimeConnection.class);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CopilotRuntimeConnections.clientOptions("copilot.", settings, "tcp"))
                .withMessageContaining("COPILOT_SDK_DEFAULT_CONNECTION");
    }

    @Test
    void cliUrlAttachesToTheServerHostAndPort() {
        assertAttached("4321", "localhost", 4321);
        assertAttached("example.com:4321", "example.com", 4321);
        assertAttached("http://127.0.0.1:4321", "127.0.0.1", 4321);
        assertAttached("https://example.com:443", "example.com", 443);
        assertAttached("[::1]:4321", "::1", 4321);

        var options = CopilotRuntimeConnections.clientOptions("copilot.",
                new Settings(null, null, null, null, "localhost:4321", "secret", null), "inprocess");
        assertThat(options.getRuntime()).isInstanceOfSatisfying(TcpRuntimeConnection.class,
                tcp -> assertThat(tcp.getConnectionToken()).isEqualTo("secret"));
    }

    @Test
    void cliUrlRejectsAddressesWithoutAHostAndPortOrWithExtraParts() {
        for (String address : List.of("localhost", "localhost:0", "localhost:65536", "ftp://localhost:1",
                "http://user:secret@localhost:1", "localhost:1/", "localhost:1?q", "localhost:1#f")) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CopilotRuntimeConnections.attach("copilot.", address, null))
                    .withMessageStartingWith("copilot.cli-url must specify a host and port")
                    .withMessageNotContaining("secret");
        }
    }

    @Test
    void logLevelIsRejectedForAnExternalServer() {
        var settings = new Settings(null, null, null, "debug", "localhost:4321", null, null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CopilotRuntimeConnections.clientOptions("copilot.", settings))
                .withMessageStartingWith("copilot.log-level cannot be combined with copilot.cli-url");
    }

    private static void assertAttached(String address, String host, int port) {
        assertThat(CopilotRuntimeConnections.attach("copilot.", address, null)).satisfies(tcp -> {
            assertThat(tcp.getHost()).isEqualTo(host);
            assertThat(tcp.getPort()).isEqualTo(port);
        });
    }

    @Test
    void requiresJna() {
        assertThat(problem("Linux", "amd64", Set.of(), Set.of(), null, null))
                .isEqualTo("copilot.connection=inprocess requires JNA on the classpath; "
                        + "add the net.java.dev.jna:jna dependency");
    }

    @Test
    void requiresASupportedPlatform() {
        assertThat(problem("SunOS", "sparcv9", JNA, Set.of(), null, null)).contains("is not supported on SunOS/sparcv9");
    }

    @Test
    void namesTheClassifierArtifactWhenTheRuntimeIsMissing() {
        assertThat(problem("Mac OS X", "aarch64", JNA, Set.of(), null, null))
                .contains("add com.microsoft:copilot-sdk-runtime:2.0.1:darwin-arm64 to the classpath")
                .contains("COPILOT_CLI_PATH");
        assertThat(problem("Linux", "amd64", JNA, Set.of(), null, null))
                .contains("com.microsoft:copilot-sdk-runtime:2.0.1:linux-x64 (glibc) or "
                        + "com.microsoft:copilot-sdk-runtime:2.0.1:linuxmusl-x64 (musl)");
        assertThat(problem("Windows 11", "arm64", JNA, Set.of(), null, null)).contains(":win32-arm64");
    }

    @Test
    void acceptsTheClassifierArtifactOnTheClasspath() {
        assertThat(problem("Linux", "aarch64", JNA, Set.of("native/linuxmusl-arm64/runtime.node"), null, null))
                .isNull();
        assertThat(problem("Mac OS X", "x86_64", JNA, Set.of("native/darwin-x64/runtime.node"), null, null)).isNull();
    }

    @Test
    void acceptsARuntimeNextToTheConfiguredOrPathCli(@TempDir Path dir) throws Exception {
        Path cli = Files.writeString(dir.resolve("copilot"), "#!/bin/sh");
        assertThat(problem("Linux", "amd64", JNA, Set.of(), cli.toString(), null)).isNotNull();
        assertThat(problem("Linux", "amd64", JNA, Set.of(), null, dir.toString())).isNotNull();

        Files.createDirectories(dir.resolve("prebuilds/linux-x64"));
        Files.writeString(dir.resolve("prebuilds/linux-x64/runtime.node"), "binary");
        assertThat(problem("Linux", "amd64", JNA, Set.of(), cli.toString(), null)).isNull();
        assertThat(problem("Linux", "amd64", JNA, Set.of(), null, dir.toString())).isNull();
    }

    private static String problem(String os, String arch, Set<String> classes, Set<String> resources,
            String cliPathEnv, String path) {
        return CopilotRuntimeConnections.inProcessRuntimeProblem("copilot.connection=inprocess", os, arch,
                classes::contains, resources::contains, cliPathEnv, path, "2.0.1");
    }
}
