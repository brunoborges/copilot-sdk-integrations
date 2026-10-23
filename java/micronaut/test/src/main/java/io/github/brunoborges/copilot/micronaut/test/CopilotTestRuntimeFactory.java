package io.github.brunoborges.copilot.micronaut.test;

import jakarta.inject.Singleton;

import com.github.copilot.CopilotClient;

import io.github.brunoborges.copilot.micronaut.CopilotAgentTelemetryDecorator;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;

/**
 * Replaces the {@link CopilotClient} of a Micronaut test with the scripted, in-memory {@link CopilotTestRuntime}, so
 * no Copilot CLI is started.
 * <p>
 * Active whenever {@code copilot-micronaut-test} is on the test class path; set
 * {@code micronaut.copilot.test-runtime.enabled=false} to talk to a real runtime instead, for example a
 * {@link io.github.brunoborges.copilot.micronaut.test.testcontainers.CopilotContainer}. The runtime is injectable,
 * records every interaction and is {@link CopilotTestRuntime#reset() reset} after each {@code @MicronautTest}
 * method.
 *
 * <pre>{@code
 * @MicronautTest
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
@Factory
@Requires(property = CopilotTestRuntimeFactory.ENABLED, notEquals = "false")
public class CopilotTestRuntimeFactory {

    static final String ENABLED = "micronaut.copilot.test-runtime.enabled";

    @Singleton
    CopilotTestRuntime copilotTestRuntime() {
        return new CopilotTestRuntime();
    }

    @Singleton
    @Replaces(CopilotClient.class)
    CopilotClient copilotTestClient(CopilotTestRuntime runtime) {
        return runtime.client();
    }

    @Singleton
    CopilotAgentTelemetryDecorator copilotTestRuntimeTelemetry(CopilotTestRuntime runtime) {
        return runtime::telemetry;
    }
}
