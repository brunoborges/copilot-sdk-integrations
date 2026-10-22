package io.github.brunoborges.copilot.quarkus.test;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import com.github.copilot.CopilotClient;

import io.github.brunoborges.copilot.quarkus.runtime.CopilotAgentTelemetryDecorator;
import io.quarkus.arc.properties.UnlessBuildProperty;

/**
 * Replaces the {@link CopilotClient} of a Quarkus test with the scripted, in-memory {@link CopilotTestRuntime}, so no
 * Copilot CLI is started.
 * <p>
 * Active whenever {@code copilot-quarkus-test} is on the test class path; set
 * {@code quarkus.copilot.test-runtime.enabled=false} (a build-time property) to talk to a real runtime instead, for
 * example a {@link io.github.brunoborges.copilot.quarkus.test.testcontainers.CopilotContainer}. The runtime is
 * injectable, records every interaction and is {@link CopilotTestRuntime#reset() reset} after each
 * {@code @QuarkusTest} method.
 *
 * <pre>{@code
 * @QuarkusTest
 * class AdvisorTests {
 *
 *     @Inject CopilotTestRuntime copilot;
 *     @Inject Advisor advisor;
 *
 *     @Test
 *     void answers() {
 *         copilot.agent("advisor").replies("Use constructor injection.");
 *         assertEquals("Use constructor injection.", advisor.ask("How?").join());
 *     }
 * }
 * }</pre>
 */
@Singleton
@UnlessBuildProperty(name = "quarkus.copilot.test-runtime.enabled", stringValue = "false", enableIfMissing = true)
public class CopilotTestRuntimeProducer {

    private final CopilotTestRuntime runtime = new CopilotTestRuntime();

    @Produces
    @Singleton
    @UnlessBuildProperty(name = "quarkus.copilot.test-runtime.enabled", stringValue = "false", enableIfMissing = true)
    CopilotTestRuntime copilotTestRuntime() {
        return runtime;
    }

    @Produces
    @Singleton
    @UnlessBuildProperty(name = "quarkus.copilot.test-runtime.enabled", stringValue = "false", enableIfMissing = true)
    CopilotClient copilotTestClient() {
        return runtime.client();
    }

    @Produces
    @Singleton
    @UnlessBuildProperty(name = "quarkus.copilot.test-runtime.enabled", stringValue = "false", enableIfMissing = true)
    CopilotAgentTelemetryDecorator copilotTestRuntimeTelemetry() {
        return runtime::telemetry;
    }
}
