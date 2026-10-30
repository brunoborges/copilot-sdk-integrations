package com.microsoft.copilot.integrations.testcontainers;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * An isolated Copilot runtime using the image contract documented by this integration.
 * The SDK remains an application dependency, not a dependency of this helper.
 */
public class CopilotContainer extends GenericContainer<CopilotContainer> {

    public static final int DEFAULT_PORT = 4321;

    private int copilotPort = DEFAULT_PORT;
    private String connectionToken = UUID.randomUUID().toString();

    /** Accepts a locally built or registry-hosted image implementing the runtime contract. */
    public CopilotContainer(String image) {
        this(DockerImageName.parse(Objects.requireNonNull(image, "image")));
    }

    public CopilotContainer(DockerImageName image) {
        super(Objects.requireNonNull(image, "image"));
        waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    }

    /** Changes the internal port; the host port is always allocated by Testcontainers. */
    public CopilotContainer withCopilotPort(int port) {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Copilot port must be between 1024 and 65535");
        }
        this.copilotPort = port;
        return self();
    }

    /** Overrides the per-container random connection secret. This is not a GitHub token. */
    public CopilotContainer withConnectionToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Connection token must not be blank");
        }
        this.connectionToken = token;
        return self();
    }

    /** Supplies runtime authentication explicitly; no host credentials are inherited. */
    public CopilotContainer withGitHubToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("GitHub token must not be blank");
        }
        return withEnv("COPILOT_GITHUB_TOKEN", token);
    }

    @Override
    protected void configure() {
        addExposedPort(copilotPort);
        addEnv("COPILOT_PORT", Integer.toString(copilotPort));
        addEnv("COPILOT_CONNECTION_TOKEN", connectionToken);
    }

    public int getCopilotPort() {
        return copilotPort;
    }

    /** Returns the SDK TCP address, without a URI scheme (also works with older SDKs). */
    public String getEndpoint() {
        String host = getHost();
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return host + ":" + getMappedPort(copilotPort);
    }

    /** Returns the legacy Java SDK cliUrl representation; the wire protocol is TCP, not HTTP. */
    public String getCliUrl() {
        return "http://" + getEndpoint();
    }

    public String getTcpConnectionToken() {
        return connectionToken;
    }
}
