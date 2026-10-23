package com.microsoft.copilot.integrations.core.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Predicate;

import com.github.copilot.AllowCopilotExperimental;
import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.RuntimeConnection;

/**
 * Applies an explicit runtime connection ({@code <prefix>connection}) to SDK client options for the framework
 * integrations, failing fast with configuration-property names instead of the SDK's option names.
 *
 * <p>The in-process connection is experimental in the SDK. It loads the runtime's native library through JNA instead of
 * starting a child process, so it needs JNA and the platform classifier artifact
 * {@code com.github:copilot-sdk-java-runtime:<version>:<classifier>} at run time, and it rejects per-process settings
 * (CLI path, arguments, working directory, environment, telemetry) and server settings (URL, TCP token).
 */
@AllowCopilotExperimental
public final class CopilotRuntimeConnections {

    static final String JNA_CLASS = "com.sun.jna.Native";
    static final String CLI_PATH_ENV = "COPILOT_CLI_PATH";
    static final String RUNTIME_FILENAME = "runtime.node";
    static final String RUNTIME_ARTIFACT = "com.github:copilot-sdk-java-runtime";

    private CopilotRuntimeConnections() {
    }

    /**
     * Pins the child-process stdio transport so that {@code COPILOT_SDK_DEFAULT_CONNECTION} cannot switch it.
     *
     * @param options options built from the other connection settings
     * @param prefix configuration prefix including the trailing dot, such as {@code copilot.}
     */
    public static void useStdio(CopilotClientOptions options, String prefix) {
        rejectConflicts(prefix, "stdio", Arrays.asList(
                conflict(options.getCliUrl() != null, prefix + "cli-url"),
                conflict(options.getTcpConnectionToken() != null, prefix + "tcp-connection-token")),
                "the stdio connection starts its own runtime");
        var stdio = RuntimeConnection.forStdio(options.getCliPath());
        if (options.getCliArgs() != null) {
            stdio.setArgs(List.of(options.getCliArgs()));
        }
        options.setConnection(stdio);
    }

    /**
     * Hosts the runtime in this process after verifying that no rejected option is set and that JNA and the native
     * runtime for this platform are available.
     *
     * @param options options built from the other connection settings
     * @param prefix configuration prefix including the trailing dot, such as {@code copilot.}
     * @throws IllegalArgumentException if a setting the in-process runtime rejects is present
     * @throws IllegalStateException if JNA or the native runtime library is missing
     */
    public static void useInProcess(CopilotClientOptions options, String prefix) {
        rejectConflicts(prefix, "inprocess", Arrays.asList(
                conflict(options.getCliPath() != null, prefix + "cli-path"),
                conflict(options.getCliArgs() != null && options.getCliArgs().length > 0, prefix + "cli-args"),
                conflict(options.getCwd() != null, prefix + "working-directory"),
                conflict(options.getCliUrl() != null, prefix + "cli-url"),
                conflict(options.getTcpConnectionToken() != null, prefix + "tcp-connection-token"),
                conflict(options.getEnvironment() != null && !options.getEnvironment().isEmpty(),
                        "CopilotClientOptions.environment"),
                conflict(options.getTelemetry() != null, "CopilotClientOptions.telemetry")),
                "the in-process runtime shares this process, so per-process settings cannot be honored and no "
                        + "separate server is used; set environment variables and the working directory on the host "
                        + "process instead");
        requireInProcessRuntime(prefix + "connection=inprocess");
        options.setConnection(RuntimeConnection.forInProcess());
    }

    /**
     * Verifies that JNA and the native runtime library for this platform can be found.
     *
     * @param setting the setting that requested the in-process runtime, used in error messages
     * @throws IllegalStateException if either is missing
     */
    public static void requireInProcessRuntime(String setting) {
        ClassLoader loader = CopilotClient.class.getClassLoader();
        String problem = inProcessRuntimeProblem(setting, System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""), name -> isClassPresent(name, loader),
                resource -> loader.getResource(resource) != null, System.getenv(CLI_PATH_ENV),
                System.getenv("PATH"), sdkVersion(loader));
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
    }

    /** Returns why the in-process runtime cannot load, or {@code null} when it looks available. */
    static String inProcessRuntimeProblem(String setting, String osName, String osArch, Predicate<String> classPresent,
            Predicate<String> resourcePresent, String cliPathEnv, String path, String sdkVersion) {
        if (!classPresent.test(JNA_CLASS)) {
            return setting + " requires JNA on the classpath; add the net.java.dev.jna:jna dependency";
        }
        List<String> classifiers = classifiers(osName, osArch);
        if (classifiers.isEmpty()) {
            return setting + " is not supported on " + osName + "/" + osArch
                    + "; the native Copilot runtime is published for Linux, macOS, and Windows on x64 and arm64";
        }
        for (String classifier : classifiers) {
            if (resourcePresent.test("native/" + classifier + "/" + RUNTIME_FILENAME)) {
                return null;
            }
        }
        if (cliPathEnv != null && !cliPathEnv.isBlank() && hasAdjacentRuntime(cliPathEnv, classifiers)) {
            return null;
        }
        String onPath = findCliOnPath(path, osName);
        if (onPath != null && hasAdjacentRuntime(onPath, classifiers)) {
            return null;
        }
        String version = sdkVersion == null ? "<copilot-sdk-java version>" : sdkVersion;
        String artifacts = classifiers.stream().map(classifier -> RUNTIME_ARTIFACT + ":" + version + ":" + classifier)
                .reduce((first, second) -> first + " (glibc) or " + second + " (musl)").orElseThrow();
        return setting + " requires the native Copilot runtime for this platform; add " + artifacts
                + " to the classpath, or set " + CLI_PATH_ENV + " to a Copilot CLI with an adjacent "
                + RUNTIME_FILENAME;
    }

    /** Platform classifiers to look for, mirroring the SDK's naming; Linux accepts either libc variant. */
    static List<String> classifiers(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = switch (osArch.toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> null;
        };
        if (arch == null) {
            return List.of();
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of("darwin-" + arch);
        }
        if (os.contains("win")) {
            return List.of("win32-" + arch);
        }
        if (os.contains("linux")) {
            return List.of("linux-" + arch, "linuxmusl-" + arch);
        }
        return List.of();
    }

    private static boolean hasAdjacentRuntime(String cliPath, List<String> classifiers) {
        try {
            Path parent = Path.of(cliPath).toAbsolutePath().normalize().getParent();
            if (parent == null) {
                return false;
            }
            if (isNonEmptyFile(parent.resolve(RUNTIME_FILENAME))) {
                return true;
            }
            for (String classifier : classifiers) {
                if (isNonEmptyFile(parent.resolve("prebuilds").resolve(classifier).resolve(RUNTIME_FILENAME))) {
                    return true;
                }
            }
        } catch (InvalidPathException | IOException | SecurityException ex) {
            return false;
        }
        return false;
    }

    private static String findCliOnPath(String path, String osName) {
        if (path == null || path.isBlank()) {
            return null;
        }
        List<String> names = osName.toLowerCase(Locale.ROOT).contains("win")
                ? List.of("copilot.exe", "copilot.cmd", "copilot.bat", "copilot")
                : List.of("copilot");
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            for (String name : names) {
                try {
                    Path candidate = Path.of(directory, name);
                    if (Files.isRegularFile(candidate)) {
                        return candidate.toRealPath().toString();
                    }
                } catch (InvalidPathException | IOException | SecurityException ex) {
                    // Unusable PATH entries are skipped, as the SDK does.
                }
            }
        }
        return null;
    }

    private static boolean isNonEmptyFile(Path path) throws IOException {
        return Files.isRegularFile(path) && Files.size(path) > 0;
    }

    private static boolean isClassPresent(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }

    private static String sdkVersion(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream("copilot-runtime.properties")) {
            if (in == null) {
                return null;
            }
            var properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version");
            return version == null || version.isBlank() ? null : version.trim();
        } catch (IOException ex) {
            return null;
        }
    }

    private static String conflict(boolean present, String name) {
        return present ? name : null;
    }

    private static void rejectConflicts(String prefix, String connection, List<String> candidates, String reason) {
        var conflicts = new ArrayList<String>();
        candidates.forEach(name -> {
            if (name != null) {
                conflicts.add(name);
            }
        });
        Assert.isTrue(conflicts.isEmpty(), () -> prefix + "connection=" + connection + " cannot be combined with "
                + String.join(", ", conflicts) + ": " + reason);
    }
}
