package io.github.brunoborges.copilot.quarkus.test.testcontainers;

import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Starts a {@link CopilotContainer} and points the extension at it through {@code quarkus.copilot.cli-url} and
 * {@code quarkus.copilot.tcp-connection-token}.
 * <p>
 * The image is required: pass it with the {@code image} init argument, or the {@code COPILOT_TEST_IMAGE} environment
 * variable. A GitHub token is read from the {@code github-token} init argument, or {@code COPILOT_GITHUB_TOKEN}.
 * Combine with {@code quarkus.copilot.test-runtime.enabled=false} so the scripted runtime does not replace the client.
 *
 * <pre>{@code
 * @QuarkusTest
 * @WithTestResource(value = CopilotContainerTestResource.class,
 *         initArgs = @ResourceArg(name = "image", value = "my-registry/copilot-cli:latest"))
 * class LiveAdvisorIT { ... }
 * }</pre>
 */
public class CopilotContainerTestResource implements QuarkusTestResourceLifecycleManager {

    private String image;
    private String gitHubToken;
    private CopilotContainer container;

    @Override
    public void init(Map<String, String> initArgs) {
        image = initArgs.getOrDefault("image", System.getenv("COPILOT_TEST_IMAGE"));
        gitHubToken = initArgs.getOrDefault("github-token", System.getenv("COPILOT_GITHUB_TOKEN"));
    }

    @Override
    public Map<String, String> start() {
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(
                    "No Copilot CLI image configured; set the 'image' init argument or COPILOT_TEST_IMAGE");
        }
        container = new CopilotContainer(image).withGitHubToken(gitHubToken);
        container.start();
        return Map.of("quarkus.copilot.cli-url", container.getCliUrl(),
                "quarkus.copilot.tcp-connection-token", container.getTcpConnectionToken());
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }
}
