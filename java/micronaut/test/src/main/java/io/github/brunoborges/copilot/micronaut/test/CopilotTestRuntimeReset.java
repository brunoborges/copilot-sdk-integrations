package io.github.brunoborges.copilot.micronaut.test;

import jakarta.inject.Singleton;

import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.context.TestContext;
import io.micronaut.test.context.TestExecutionListener;

/** Resets the {@link CopilotTestRuntime} after each {@code @MicronautTest} method. */
@Singleton
@Requires(classes = TestExecutionListener.class)
@Requires(property = CopilotTestRuntimeFactory.ENABLED, notEquals = "false")
public class CopilotTestRuntimeReset implements TestExecutionListener {

    private final BeanProvider<CopilotTestRuntime> runtime;

    public CopilotTestRuntimeReset(BeanProvider<CopilotTestRuntime> runtime) {
        this.runtime = runtime;
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        runtime.ifPresent(CopilotTestRuntime::reset);
    }
}
