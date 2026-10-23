package com.microsoft.copilot.integrations.springboot.test.testcontainers;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A Testcontainers container running a headless Copilot CLI server ({@code copilot --server --port ...}).
 *
 * <p>No official image exists, so the image must be supplied; see the testing guide for a sample Dockerfile. The
 * image is expected to:
 * <ul>
 * <li>start the Copilot CLI in server mode, reachable on {@link #DEFAULT_PORT} (or the port configured with
 * {@link #withCopilotPort(int)}, also passed to the container as the {@code COPILOT_PORT} environment variable);</li>
 * <li>require the TCP connection token passed as {@code COPILOT_CONNECTION_TOKEN};</li>
 * <li>authenticate with the GitHub token passed as {@code COPILOT_GITHUB_TOKEN}, when one is configured;</li>
 * <li>log {@code listening on port <n>} once ready.</li>
 * </ul>
 *
 * <p>Annotate the container with {@code @ServiceConnection} to point the Copilot auto-configuration at it.
 */
public class CopilotContainer extends GenericContainer<CopilotContainer> {

    /** The default port the Copilot CLI server listens on inside the container. */
    public static final int DEFAULT_PORT = 4321;

    private int port = DEFAULT_PORT;

    private String connectionToken = UUID.randomUUID().toString();

    public CopilotContainer(String dockerImageName) {
        this(DockerImageName.parse(dockerImageName));
    }

    public CopilotContainer(DockerImageName dockerImageName) {
        super(Objects.requireNonNull(dockerImageName, "dockerImageName"));
        waitingFor(Wait.forLogMessage("(?i).*listening on port \\d+.*", 1)
                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    /** Sets the port the Copilot CLI server listens on inside the container. */
    public CopilotContainer withCopilotPort(int port) {
        this.port = port;
        return self();
    }

    /** Sets the TCP connection token clients must present; a random token is used by default. */
    public CopilotContainer withConnectionToken(String connectionToken) {
        this.connectionToken = connectionToken;
        return self();
    }

    /**
     * Sets the GitHub token the Copilot CLI authenticates with ({@code COPILOT_GITHUB_TOKEN}); a {@code null} token
     * is ignored.
     */
    public CopilotContainer withGitHubToken(String gitHubToken) {
        return gitHubToken == null ? self() : withEnv("COPILOT_GITHUB_TOKEN", gitHubToken);
    }

    @Override
    protected void configure() {
        addExposedPort(port);
        addEnv("COPILOT_PORT", String.valueOf(port));
        if (connectionToken != null) {
            addEnv("COPILOT_CONNECTION_TOKEN", connectionToken);
        }
    }

    /** The port the Copilot CLI server listens on inside the container. */
    public int getCopilotPort() {
        return port;
    }

    /** The URL clients use to reach the Copilot CLI server, suitable for {@code copilot.cli-url}. */
    public String getCliUrl() {
        return "http://" + getHost() + ":" + getMappedPort(port);
    }

    /** The TCP connection token, suitable for {@code copilot.tcp-connection-token}. */
    public String getTcpConnectionToken() {
        return connectionToken;
    }
}
