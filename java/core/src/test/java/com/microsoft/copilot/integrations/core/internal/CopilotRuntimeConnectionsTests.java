package com.microsoft.copilot.integrations.core.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.copilot.AllowCopilotExperimental;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.StdioRuntimeConnection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@AllowCopilotExperimental
class CopilotRuntimeConnectionsTests {

    private static final Set<String> JNA = Set.of(CopilotRuntimeConnections.JNA_CLASS);

    @Test
    void inProcessReportsEveryRejectedSetting() {
        var options = new CopilotClientOptions().setCliPath("/bin/copilot").setCliArgs(new String[] {"--x"})
                .setCwd("/tmp").setEnvironment(Map.of("A", "B"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CopilotRuntimeConnections.useInProcess(options, "copilot."))
                .withMessageStartingWith("copilot.connection=inprocess cannot be combined with copilot.cli-path, "
                        + "copilot.cli-args, copilot.working-directory, CopilotClientOptions.environment:");
        assertThat(options.getConnection()).isNull();
    }

    @Test
    void inProcessRejectsServerSettings() {
        var options = new CopilotClientOptions().setCliUrl("localhost:3000").setTcpConnectionToken("t");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CopilotRuntimeConnections.useInProcess(options, "quarkus.copilot."))
                .withMessageContaining("quarkus.copilot.cli-url, quarkus.copilot.tcp-connection-token");
    }

    @Test
    void stdioPinsTheConfiguredExecutableAndArguments() {
        var options = new CopilotClientOptions().setCliPath("/bin/copilot").setCliArgs(new String[] {"--x"});

        CopilotRuntimeConnections.useStdio(options, "copilot.");

        assertThat(options.getConnection()).isInstanceOfSatisfying(StdioRuntimeConnection.class, stdio -> {
            assertThat(stdio.getPath()).isEqualTo("/bin/copilot");
            assertThat(stdio.getArgs()).containsExactly("--x");
        });
    }

    @Test
    void stdioRejectsAnExistingServer() {
        var options = new CopilotClientOptions().setCliUrl("localhost:3000");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CopilotRuntimeConnections.useStdio(options, "copilot."))
                .withMessageStartingWith("copilot.connection=stdio cannot be combined with copilot.cli-url");
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
                .contains("add com.github:copilot-sdk-java-runtime:1.0.24:darwin-arm64 to the classpath")
                .contains("COPILOT_CLI_PATH");
        assertThat(problem("Linux", "amd64", JNA, Set.of(), null, null))
                .contains("com.github:copilot-sdk-java-runtime:1.0.24:linux-x64 (glibc) or "
                        + "com.github:copilot-sdk-java-runtime:1.0.24:linuxmusl-x64 (musl)");
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
                classes::contains, resources::contains, cliPathEnv, path, "1.0.24");
    }
}
