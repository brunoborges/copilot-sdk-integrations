package com.microsoft.copilot.integrations.core.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Predicate;

import com.microsoft.copilot.AllowCopilotExperimental;
import com.microsoft.copilot.CopilotClient;
import com.microsoft.copilot.rpc.CopilotClientMode;
import com.microsoft.copilot.rpc.CopilotClientOptions;
import com.microsoft.copilot.rpc.RuntimeConnection;
import com.microsoft.copilot.rpc.TcpRuntimeConnection;

/**
 * Builds SDK client options from the framework integrations' connection settings, failing fast with
 * configuration-property names instead of the SDK's option names.
 *
 * <p>The integrations always use {@link CopilotClientMode#CODING_AGENT}, the Copilot CLI-equivalent defaults. When
 * {@code <prefix>connection} is omitted, a {@code <prefix>cli-url} attaches to that server over TCP; otherwise
 * {@code COPILOT_SDK_DEFAULT_CONNECTION=inprocess} selects the in-process runtime and anything else spawns a stdio
 * child process.
 *
 * <p>The in-process connection is experimental in the SDK. It loads the runtime's native library through JNA instead of
 * starting a child process, so it needs JNA and the platform classifier artifact
 * {@code com.microsoft:copilot-sdk-runtime:<version>:<classifier>} at run time, and it rejects per-process settings
 * (CLI path, arguments, working directory) and server settings (URL, TCP token).
 */
@AllowCopilotExperimental
public final class CopilotRuntimeConnections {

    static final String JNA_CLASS = "com.sun.jna.Native";
    static final String CLI_PATH_ENV = "COPILOT_CLI_PATH";
    static final String DEFAULT_CONNECTION_ENV = "COPILOT_SDK_DEFAULT_CONNECTION";
    static final String RUNTIME_FILENAME = "runtime.node";
    static final String RUNTIME_ARTIFACT = "com.microsoft:copilot-sdk-runtime";

    /** Explicit runtime transport selected by {@code <prefix>connection}. */
    public enum Transport {
        /** Child process over stdin/stdout, regardless of {@code COPILOT_SDK_DEFAULT_CONNECTION}. */
        STDIO,
        /** Runtime hosted inside this JVM (experimental). */
        INPROCESS
    }

    /**
     * Connection settings shared by the framework integrations; {@code null} values are unset.
     *
     * @param cliPath explicit executable path for a spawned runtime
     * @param cliArgs extra arguments for a spawned runtime
     * @param workingDirectory working directory for a spawned runtime
     * @param logLevel runtime log level for a runtime owned by the client
     * @param cliUrl existing server address (port, host:port, or HTTP-style URI)
     * @param tcpConnectionToken authentication token for an existing TCP server
     * @param transport explicit transport, or {@code null} for the default described on the class
     */
    public record Settings(String cliPath, List<String> cliArgs, String workingDirectory, String logLevel,
            String cliUrl, String tcpConnectionToken, Transport transport) {

        public Settings {
            cliArgs = cliArgs == null ? List.of() : List.copyOf(cliArgs);
        }
    }

    private CopilotRuntimeConnections() {
    }

    /**
     * Builds client options with an explicit mode and runtime connection.
     *
     * @param prefix configuration prefix including the trailing dot, such as {@code copilot.}
     * @param settings the integration's connection settings
     * @return new client options
     * @throws IllegalArgumentException if settings conflict or the server address is invalid
     * @throws IllegalStateException if the in-process runtime is requested but JNA or the native runtime is missing
     */
    public static CopilotClientOptions clientOptions(String prefix, Settings settings) {
        return clientOptions(prefix, settings, System.getenv(DEFAULT_CONNECTION_ENV));
    }

    static CopilotClientOptions clientOptions(String prefix, Settings settings, String defaultConnection) {
        var options = new CopilotClientOptions().setMode(CopilotClientMode.CODING_AGENT);
        RuntimeConnection runtime = connection(prefix, settings, defaultConnection);
        if (settings.logLevel() != null) {
            Assert.isTrue(!(runtime instanceof TcpRuntimeConnection), () -> prefix + "log-level cannot be combined "
                    + "with " + prefix + "cli-url: the external server owns its runtime settings");
            options.getObservability().setLogLevel(settings.logLevel());
        }
        return options.setRuntime(runtime);
    }

    /**
     * Attaches to an existing runtime server over TCP.
     *
     * @param prefix configuration prefix including the trailing dot, used in error messages
     * @param address server address: a port, {@code host:port}, or {@code http(s)://host:port}
     * @param connectionToken optional server token
     * @return the attached connection
     * @throws IllegalArgumentException if the address has no valid host and port, or has credentials, a path, a
     *         query, or a fragment
     */
    public static TcpRuntimeConnection attach(String prefix, String address, String connectionToken) {
        String message = prefix + "cli-url must specify a host and port (1-65535), or just a port; "
                + "credentials, paths, queries, and fragments are not supported";
        URI uri;
        try {
            String normalized = address.matches("[0-9]+") ? "http://localhost:" + address : address;
            uri = URI.create(normalized.contains("://") ? normalized : "http://" + normalized);
        } catch (IllegalArgumentException ex) {
            // URI parser messages may contain credentials; expose only the property name.
            throw new IllegalArgumentException(message);
        }
        Assert.isTrue(("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null && uri.getPort() > 0 && uri.getPort() <= 65535
                        && uri.getRawUserInfo() == null
                        && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                        && uri.getRawQuery() == null && uri.getRawFragment() == null,
                message);
        String host = uri.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        try {
            return RuntimeConnection.forTcp(host, uri.getPort()).setConnectionToken(connectionToken);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(message);
        }
    }

    private static RuntimeConnection connection(String prefix, Settings settings, String defaultConnection) {
        if (settings.transport() == Transport.STDIO) {
            rejectConflicts(prefix, "stdio", Arrays.asList(
                    conflict(settings.cliUrl() != null, prefix + "cli-url"),
                    conflict(settings.tcpConnectionToken() != null, prefix + "tcp-connection-token")),
                    "the stdio connection starts its own runtime");
            return stdio(settings);
        }
        if (settings.transport() == Transport.INPROCESS) {
            rejectConflicts(prefix, "inprocess", Arrays.asList(
                    conflict(settings.cliPath() != null, prefix + "cli-path"),
                    conflict(!settings.cliArgs().isEmpty(), prefix + "cli-args"),
                    conflict(settings.workingDirectory() != null, prefix + "working-directory"),
                    conflict(settings.cliUrl() != null, prefix + "cli-url"),
                    conflict(settings.tcpConnectionToken() != null, prefix + "tcp-connection-token")),
                    "the in-process runtime shares this process, so per-process settings cannot be honored and no "
                            + "separate server is used; set environment variables and the working directory on the "
                            + "host process instead");
            requireInProcessRuntime(prefix + "connection=inprocess");
            return RuntimeConnection.forInProcess();
        }
        if (settings.cliUrl() != null) {
            return attach(prefix, settings.cliUrl(), settings.tcpConnectionToken());
        }
        if (defaultConnection == null || defaultConnection.isEmpty() || "stdio".equalsIgnoreCase(defaultConnection)) {
            return stdio(settings);
        }
        Assert.isTrue("inprocess".equalsIgnoreCase(defaultConnection), () -> "Invalid " + DEFAULT_CONNECTION_ENV
                + " value '" + defaultConnection + "'. Expected 'inprocess', 'stdio', or unset.");
        // An explicit executable still selects the child process, as in earlier SDK releases.
        return settings.cliPath() != null ? stdio(settings) : RuntimeConnection.forInProcess();
    }

    private static RuntimeConnection stdio(Settings settings) {
        var stdio = RuntimeConnection.forSpawnedStdio(settings.cliPath());
        if (!settings.cliArgs().isEmpty()) {
            stdio.setArgs(settings.cliArgs());
        }
        if (settings.workingDirectory() != null) {
            stdio.setWorkingDirectory(settings.workingDirectory());
        }
        return stdio;
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
        String version = sdkVersion == null ? "<copilot-sdk version>" : sdkVersion;
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
